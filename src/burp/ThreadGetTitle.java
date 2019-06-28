package burp;

import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

//////////////////ThreadGetTitle block/////////////
//no need to pass BurpExtender object to these class, IBurpExtenderCallbacks object is enough 
class ThreadGetTitle{
	private Set<String> domains;
	private List<Producer> plist;

	private static IBurpExtenderCallbacks callbacks = BurpExtender.getCallbacks();//静态变量，burp插件的逻辑中，是可以保证它被初始化的。;
	public PrintWriter stdout = new PrintWriter(callbacks.getStdout(), true);
	public PrintWriter stderr = new PrintWriter(callbacks.getStderr(), true);
	public IExtensionHelpers helpers = callbacks.getHelpers();

	public ThreadGetTitle(Set<String> domains) {
		this.domains = domains;
	}

	public void Do(){
		stdout.println("~~~~~~~~~~~~~Start threading Get Title~~~~~~~~~~~~~ total task number: "+domains.size());
		BlockingQueue<String> domainQueue = new LinkedBlockingQueue<String>();//use to store domains
		domainQueue.addAll(domains);

		plist = new ArrayList<Producer>();

		for (int i=0;i<=50;i++) {
			Producer p = new Producer(domainQueue,i);
			//Producer p = new Producer(callbacks,domainQueue,sharedQueue,i);
			p.start();
			plist.add(p);
		}

		long waitTime = 0; 
		while(true) {//to wait all threads exit.
			if (domainQueue.isEmpty() && isAllProductorFinished()) {
				stdout.println("~~~~~~~~~~~~~Get Title Done~~~~~~~~~~~~~");
				break;
			}else if(domainQueue.isEmpty() && waitTime >=10*60*1000){
				stdout.println("~~~~~~~~~~~~~Get Title Done(force exits due to time out)~~~~~~~~~~~~~");
				break;
			}else {
				try {
					Thread.sleep(60*1000);//1分钟
					waitTime =waitTime+60*1000;
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				continue;//unnecessary
			}
		}

		return;
	}

	boolean isAllProductorFinished(){
		int i = 0;
		for (Producer p:plist) {
			if(p.isAlive()) {
				i = i+1;
			}
		}
		if (i>0){
			stdout.println( "~~~~~~~~~~~~~"+i +" productors are still alive~~~~~~~~~~~~~");
			return false;
		}else{
			stdout.println( "~~~~~~~~~~~~~All productor threads exited ~~~~~~~~~~~~~");
			return true;
		}
	}

	public void stopThreads() {
		for (Producer p:plist) {
			p.stopThread();
		}
		stdout.println("~~~~~~~~~~~~~All stop message sent! wait them to exit~~~~~~~~~~~~~");
	}
}

/*
 * do request use method of burp
 * return IResponseInfo object Set
 *
 */

class Producer extends Thread {//Producer do
	private final BlockingQueue<String> domainQueue;//use to store domains
	private int threadNo;
	private boolean stopflag = false;

	private static IBurpExtenderCallbacks callbacks = BurpExtender.getCallbacks();//静态变量，burp插件的逻辑中，是可以保证它被初始化的。;
	public PrintWriter stdout = new PrintWriter(callbacks.getStdout(), true);
	public PrintWriter stderr = new PrintWriter(callbacks.getStderr(), true);
	public IExtensionHelpers helpers = callbacks.getHelpers();

	public Producer(BlockingQueue<String> domainQueue,int threadNo) {
		this.threadNo = threadNo;
		this.domainQueue = domainQueue;
		stopflag= false;
	}

	public void stopThread() {
		stopflag = true;
	}

	@Override
	public void run() {
		while(true){
			try {
				if (domainQueue.isEmpty() || stopflag) {
					//stdout.println(threadNo+" Producer exited");
					break;
				}
				String host = domainQueue.take();
				int leftTaskNum = domainQueue.size();

				//第一步：IP解析
				Set<String> IPSet = new HashSet<>();
				Set<String> CDNSet = new HashSet<>();
				if (Commons.isValidIP(host)) {
					IPSet.add(host);
					CDNSet.add("");
				}else {
					HashMap<String,Set<String>> result = Commons.dnsquery(host);
					IPSet = result.get("IP");
					CDNSet = result.get("CDN");
				}

				if (IPSet.size() <= 0) {
					continue;
				}else {//默认过滤私有IP
					String ip = new ArrayList<>(IPSet).get(0);
					if (IPAddress.isPrivateIPv4(ip)) {
						continue;
					}
				}



				//第二步：对成功解析的host进行HTTP请求。
				Getter getter = new Getter(helpers);
				IHttpService http = helpers.buildHttpService(host,80,"http");
				IHttpService https = helpers.buildHttpService(host,443,"https");

				byte[] http_Request = helpers.buildHttpRequest(new URL(http.toString()));
				String cookie = TitlePanel.getTextFieldCookie().getText().trim();
				http_Request = buildCookieRequest(cookie,http_Request);
				IHttpRequestResponse http_Messageinfo = callbacks.makeHttpRequest(http, http_Request);
				//stdout.println("messageinfo"+JSONObject.toJSONString(messageinfo));
				//这里有2种异常情况：1.请求失败（连IP都解析不了,已经通过第一步过滤了）；2.请求成功但是响应包为空（可以解析IP，比如内网域名）。
				//第一种请求在这里就结束了，第二种情况的请求信息会传递到consumer中进行IP获取的操作。
				byte[] http_Body = getter.getBody(false, http_Messageinfo);
				int http_Status = getter.getStatusCode(http_Messageinfo);//当为第二种异常时，httpStatus == -1
				int http_response_length = 0;
				if (http_Body != null) {
					http_response_length = http_Body.length;
				}
				
				String location = getter.getHeaderValueOf(false, http_Messageinfo, "Location");

				byte[] https_Request = helpers.buildHttpRequest(new URL(https.toString()));
				http_Request = buildCookieRequest(cookie,https_Request);
				IHttpRequestResponse https_Messageinfo = callbacks.makeHttpRequest(https, https_Request);
				byte[] https_Body = getter.getBody(false, https_Messageinfo);
				int https_Status = getter.getStatusCode(https_Messageinfo);//当为第二种异常时，httpStatus == -1
				int https_response_length = 0;
				if (https_Body != null) {
					https_response_length = https_Body.length;
				}

				Set<IHttpRequestResponse> tmpSet = new HashSet<IHttpRequestResponse>();


				//去重
				//if (http_Status == https_Status && Arrays.equals(http_Body,https_Body)) {//parameters can be null,great
				if (http_Status == https_Status && https_response_length == http_response_length) {//body长度相同就行了
					tmpSet.add(http_Messageinfo);
				}else if( 300 <= http_Status && http_Status <400 && location.equalsIgnoreCase(https.toString()+"/") ) {//redirect to https
					tmpSet.add(https_Messageinfo);
				}else {
					tmpSet.add(http_Messageinfo);
					tmpSet.add(https_Messageinfo);
				}

				//根据请求有效性分类处理
				Iterator<IHttpRequestResponse> it = tmpSet.iterator();
				while (it.hasNext()) {
					IHttpRequestResponse item = it.next();

					String url = item.getHttpService().toString();
					int status = getter.getStatusCode(item);

					if (item.getResponse() == null) {
						stdout.println(String.format("%s tasks left || --- [%s] --- has no response",leftTaskNum,url));
						TitlePanel.getTitleTableModel().addNewNoResponseDomain(host, IPSet);
					}else if(status >= 500){
						stdout.println(String.format("%s tasks left || --- [%s] --- status code >= 500",leftTaskNum,url));
						TitlePanel.getTitleTableModel().addNewNoResponseDomain(host, IPSet);
					}else {
						byte[] byteBody = getter.getBody(false, item);
						String body = new String(byteBody);

						String URLAndbodyText = item.getHttpService().toString()+body;

						LineEntry linefound = findHistory(url);
						boolean isChecked = false;
						String comment = "";
						boolean isNew = true;

						if (null != linefound) {
							isChecked = linefound.isChecked();
							comment = linefound.getComment();
							//stderr.println(new String(linefound.getResponse()));
							try {
								String text = linefound.getUrl()+linefound.getBodyText();
								if (text.equalsIgnoreCase(URLAndbodyText) && isChecked) {
									isNew = false;
								}
							}catch(Exception err) {
								err.printStackTrace(stderr);
							}
						}
						BurpExtender.getGui().getTitlePanel().getTitleTableModel().addNewLineEntry(new LineEntry(item,isNew,isChecked,comment,IPSet,CDNSet));

						//stdout.println(new LineEntry(messageinfo,true).ToJson());

						stdout.println(String.format("%s tasks left || +++ [%s] +++ get title done",leftTaskNum,url));
					}
				}
			} catch (Exception error) {
				error.printStackTrace(stderr);
				continue;//unnecessary
				//java.lang.RuntimeException can't been catched, why?
			}
		}
	}

	public LineEntry findHistory(String url) {
		List<LineEntry> HistoryLines = BurpExtender.getGui().getTitlePanel().getBackupLineEntries();
		if (HistoryLines == null) return null;
		for (LineEntry line:HistoryLines) {
			line.setHelpers(helpers);
			if (url.equalsIgnoreCase(line.getUrl())) {
				return line;
			}
			
			String protocol = url.trim().split("://")[0];
			String host = url.trim().split("://")[1];
			
			String lineProtol = line.getUrl().split("://")[0];
			List<String> lineHost = Arrays.asList(line.getIP().trim().split(","));
			if (protocol.equalsIgnoreCase(lineProtol) && lineHost.contains(host)) {
				return line;
			}
		}
		return null;
	}
	
	public LineEntry findHistorynew(String url,String IP) {
		List<LineEntry> HistoryLines = BurpExtender.getGui().getTitlePanel().getBackupLineEntries();
		if (HistoryLines == null) return null;
		for (LineEntry line:HistoryLines) {
			line.setHelpers(helpers);
			
			String host = url.trim().split("://")[1];
			
			String lineHost= line.getUrl().split("://")[1];
			List<String> lineIPList = Arrays.asList(line.getIP().trim().split(","));
			lineIPList.add(lineHost);
			if (lineIPList.contains(host) || lineIPList.contains(IP)) {
				return line;
			}
		}
		return null;
	}
	
	
	public byte[] buildCookieRequest(String cookie, byte[] request) {
		if (cookie != null && !cookie.equals("")){
			if (!cookie.startsWith("Cookie: ")){
				cookie = "Cookie: "+cookie;
			}
			List<String > newHeader = helpers.analyzeRequest(request).getHeaders();
			int bodyOffset = helpers.analyzeRequest(request).getBodyOffset();
			byte[] byte_body = Arrays.copyOfRange(request, bodyOffset, request.length);
			newHeader.add(cookie);
			request = helpers.buildHttpMessage(newHeader,byte_body);
		}
		return request;
	}
}
