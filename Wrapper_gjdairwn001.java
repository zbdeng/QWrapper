import java.io.File;
import java.io.IOException;
import java.sql.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpException;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.cookie.CookiePolicy;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Charsets;
import com.google.common.io.Files;
import com.qunar.qfwrapper.bean.booking.BookingInfo;
import com.qunar.qfwrapper.bean.booking.BookingResult;
import com.qunar.qfwrapper.bean.search.FlightDetail;
import com.qunar.qfwrapper.bean.search.FlightSearchParam;
import com.qunar.qfwrapper.bean.search.FlightSegement;
import com.qunar.qfwrapper.bean.search.OneWayFlightInfo;
import com.qunar.qfwrapper.bean.search.ProcessResultInfo;
import com.qunar.qfwrapper.constants.Constants;
import com.qunar.qfwrapper.exception.QFHttpClientException;
import com.qunar.qfwrapper.interfaces.QunarCrawler;
import com.qunar.qfwrapper.util.QFGetMethod;
import com.qunar.qfwrapper.util.QFHttpClient;
import com.qunar.qfwrapper.util.QFPostMethod;

/**
 * 
 * 美国西南航空
 *@version   V1.0 
 *@date  2014-7-7 下午5:19:20
 */
public class Wrapper_gjdairwn001 implements QunarCrawler{
	private Logger logger = LoggerFactory.getLogger(Wrapper_gjdairwn001.class);

	@Override
	public String getHtml(FlightSearchParam param) {
		QFHttpClient httpClient = new QFHttpClient(param, false);
		httpClient.getParams().setCookiePolicy(CookiePolicy.BROWSER_COMPATIBILITY);
		
		try {
			Result result = sendFirst(httpClient, param);
			String html = "";
			if (result.isSuccess()) {
				html = sendSecond(httpClient, param, result.getLocation());
			} else {
				html = result.getContent();
			}
			return html;
		} catch(Exception e) {
			logger.error("美国西南航空(gjdairwn001): ", e);
		}
        return "Exception";
	}

	@Override
	public ProcessResultInfo process(String html, FlightSearchParam param) {
		ProcessResultInfo result = new ProcessResultInfo();

		if ("Exception".equals(html)) {
			result.setRet(false);
			result.setStatus(Constants.CONNECTION_FAIL);
			return result;	
		}
		if (html.contains("The departure date selected is beyond our current booking schedule")){
			result.setRet(false);
			result.setStatus(Constants.INVALID_DATE);
			return result;	
		}
		if (html.contains("We are currently unable to complete your request due to an undefined error")) {
			result.setRet(false);
			result.setStatus(Constants.INVALID_AIRLINE);
			return result;
		}
		
		try {
			return parser(html, param);
		} catch (Exception e) {
			logger.error("美国西南航空(gjdairwn001): ", e);
			result.setRet(false);
			result.setStatus(Constants.PARSING_FAIL);
			return result;
		}
	}

	@Override
	public BookingResult getBookingInfo(FlightSearchParam param) {
		String bookingUrlPre = "http://www.southwest.com/flight/search-flight.html?int=HOMEQBOMAIR";
		BookingResult bookingResult = new BookingResult();
		
		BookingInfo bookingInfo = new BookingInfo();
		bookingInfo.setAction(bookingUrlPre);
		bookingInfo.setMethod("post");
		
		String[] depDateArray = param.getDepDate().split("-");
		String depDate = depDateArray[1] + "/" + depDateArray[2] + "/" + depDateArray[0];
		
		Map<String, String> map = new LinkedHashMap<String, String>();
		map.put("defaultBugFareType", "DOLLARS");
		map.put("disc", "");
		map.put("submitButton", "");
		map.put("previouslySelectedBookingWidgetTab", "");     
		map.put("originAirportButtonClicked", "no");
		map.put("destinationAirportButtonClicked", "no");
		map.put("returnAirport", "");
		map.put("originAirport", param.getDep());
		map.put("destinationAirport", param.getArr());
		map.put("outboundDateString", depDate);
		map.put("outboundTimeOfDay", "ANYTIME");
		map.put("returnTimeOfDay", "ANYTIME");
		map.put("adultPassengerCount", "1");
		map.put("seniorPassengerCount", "0");
		
		bookingInfo.setInputs(map);		
		bookingResult.setData(bookingInfo);
		bookingResult.setRet(true);
		return bookingResult;
	}
	
	public ProcessResultInfo parser (String html, FlightSearchParam param) {
		ProcessResultInfo result = new ProcessResultInfo();
		List<OneWayFlightInfo> flightList = new ArrayList<OneWayFlightInfo>();
		
		// 价格列表页面
		String table = StringUtils.substringBetween(html, "id=\"faresOutbound\"", "<input id=\"roundTripField\"");
		// 每条航班价格
		String[] tr = table.split("outbound_flightRow_");
		// 航班信息
		for (int i = 1; i < tr.length; i++) {
			String trStr = tr[i];
			OneWayFlightInfo flightInfo = new OneWayFlightInfo();
			
			List<FlightSegement> segs = new ArrayList<FlightSegement>();
			FlightDetail flightDetail = new FlightDetail();
			List<String> flightNoList = new ArrayList<String>();
			
			String[] priceInfo = StringUtils.substringsBetween(trStr, "<td class=\"price_column", "</td>");
			// 价格
			String price = "";
			// 货币
			String currencyUnit = "USD";
			// 航班价格信息
			for (int j = priceInfo.length-1; j >= 0; j--) {
				String p = priceInfo[j];
				if (p.contains("class=\"unavailable\"")) {
					continue;
				} 
				price = StringUtils.substringBetween(p, "<label class=\"product_price\">", "</label>").replaceAll("[^0-9.]", "");
				String value = getInputValue(p);
				String flights = value.split(",,")[1];
				String regex = flights.split(",")[10];
				String[] flightArray = flights.split(regex);
				// 航段信息
				for (String flight : flightArray) {
					String[] array = flight.split(",");
					
					segs.add(getSegement(array, param));
					flightNoList.add("WN" + array[1]);
				}
				// 航班详细信息
				flightDetail.setDepdate(Date.valueOf(param.getDepDate()));
				flightDetail.setFlightno(flightNoList);
				flightDetail.setMonetaryunit(currencyUnit);
				flightDetail.setPrice(Math.ceil(Double.parseDouble(price)));
				flightDetail.setDepcity(param.getDep());
				flightDetail.setArrcity(param.getArr());
				flightDetail.setWrapperid(param.getWrapperid());
				
				flightInfo.setDetail(flightDetail);
				flightInfo.setInfo(segs);
				flightList.add(flightInfo);
				
				break;
			}
		}
		
		if (flightList.size() > 0) {
			result.setRet(true);
			result.setStatus(Constants.SUCCESS);
			result.setData(flightList);
		} else {
			result.setRet(false);
			result.setStatus(Constants.NO_RESULT);
		}
		return result;
	}
	
	public Result sendFirst(QFHttpClient httpClient, FlightSearchParam param) 
			throws QFHttpClientException, HttpException, IOException {
		QFPostMethod post = new QFPostMethod("http://www.southwest.com/flight/search-flight.html?int=HOMEQBOMAIR");
		post.setFollowRedirects(false);
		// 出发日期转换: 月/日/年
		String[] depDateArray = param.getDepDate().split("-");
		String depDate = depDateArray[1] + "/" + depDateArray[2] + "/" + depDateArray[0];
		NameValuePair[] data = new NameValuePair[]{
				new NameValuePair("defaultBugFareType", "DOLLARS"),
				new NameValuePair("disc", ""),
				new NameValuePair("submitButton", ""),
				new NameValuePair("previouslySelectedBookingWidgetTab", ""),     
				new NameValuePair("originAirportButtonClicked", "no"),
				new NameValuePair("destinationAirportButtonClicked", "no"),
				new NameValuePair("returnAirport", ""),
				new NameValuePair("originAirport", param.getDep()),
				new NameValuePair("destinationAirport", param.getArr()),
				new NameValuePair("outboundDateString", depDate),
				new NameValuePair("outboundTimeOfDay", "ANYTIME"),
				new NameValuePair("returnTimeOfDay", "ANYTIME"),
				new NameValuePair("adultPassengerCount", "1"),
				new NameValuePair("seniorPassengerCount", "0")
		};
		post.setRequestBody(data);
		post.setRequestHeader("Referer", "http://www.southwest.com/");
		post.setRequestHeader("Host", "www.southwest.com");
		
		Result result = new Result();
		try {
			int state = httpClient.executeMethod(post);
			if ((state == 302) || (state == 301)) {	// 成功状态
				Header header = post.getResponseHeader("Location");
				if (header != null) {
					String location = header.getValue();
					result.setLocation(location);
					result.setSuccess(true);
				}
			} else if (state == 200) { // 失败状态
				String html = post.getResponseBodyAsString();
				// 返回html用来分析失败原因
				result.setContent(html);
				result.setSuccess(false);
			}
			return result;
		} finally {
			if (post != null) {
				post.releaseConnection();
			}
		}
	}
	
	public String sendSecond(QFHttpClient httpClient, FlightSearchParam param, String url) 
			throws QFHttpClientException, HttpException, IOException  {
		QFGetMethod get = new QFGetMethod(url);
		
		String cookie = StringUtils.join(httpClient.getState().getCookies(),"; ");
		httpClient.getState().clearCookies();
		get.addRequestHeader("Cookie", cookie);
		get.setRequestHeader("Referer", "http://www.southwest.com/");
		get.setRequestHeader("Host", "www.southwest.com");
		
		try {
			httpClient.executeMethod(get);
			return get.getResponseBodyAsString();
		} finally {
			if (get != null) {
				get.releaseConnection();
			}
		}
	}
	
	public FlightSegement getSegement(String[] array, FlightSearchParam param) {
		// 航班号
		FlightSegement segement = new FlightSegement("WN" + array[1]);
		// 出发机场
		segement.setDepairport(array[2]);
		// 到达机场
		segement.setArrairport(array[3]);
		// 出发时间
		segement.setDeptime(convertTime(array[4]));
		// 到达时间
		segement.setArrtime(convertTime(array[5]));
		// 出发日间
		//String deptDetp = value.split(",")[0].replace(" ", "-");
		segement.setDepDate(param.getDepDate());
		//TODO 根据搜索出来的航班, 都是同一天到达的航班
		segement.setArrDate(param.getDepDate());
		
		return segement;
	}
	
	public String getInputValue(String str) {
		String input = StringUtils.substringBetween(str, "class=\"upsellOutboundRadio\"", "<input");
		return StringUtils.substringBetween(input, "value=\"", "\"/>");
	}
	
	/**
	 * 方法：12进制 转 24进制
	 */
	private String convertTime(String time){
		String[] timeInfo = time.split(" ");
		if(timeInfo[1].equals("PM")){
			String[] times = timeInfo[0].split(":");
			int hourNum = Integer.parseInt(times[0]);
			if(hourNum == 12)
				return time.split(" ")[0];
			int finalHour = (hourNum + 12)%24;
			return finalHour + ":" + times[1];
		}
		return time.split(" ")[0];
	}
	
	public static void main(String[] args) throws IOException {
		Wrapper_gjdairwn001 wrapper = new Wrapper_gjdairwn001();
		//航班搜索条件类
		FlightSearchParam searchParam = new FlightSearchParam();
		
		searchParam.setDep("DEN");
		searchParam.setArr("SEA");
		searchParam.setDepDate("2014-08-13");
		searchParam.setWrapperid("gjdairwn001");
		
		long startTime = System.currentTimeMillis() ;
		
		String html = "";
		boolean falg = false;
		html = wrapper.getHtml(searchParam);
		if (falg) {
			html = Files.toString(new File("D:\\test.html"),Charsets.UTF_8);
		} else {
			Files.write(html, new File("D:\\test.html"), Charsets.UTF_8);
		}
		
		ProcessResultInfo result = new ProcessResultInfo();
		result = wrapper.process(html, searchParam);

		long endTime = System.currentTimeMillis() ;
		System.out.println("共花费时间(s)：" + (endTime-startTime)/1000);
		
		if(result.isRet() && result.getStatus().equals(Constants.SUCCESS))
		{
			@SuppressWarnings("unchecked")
			List<OneWayFlightInfo> flightList = (List<OneWayFlightInfo>) result.getData();
			for (OneWayFlightInfo in : flightList){
				System.out.println("************" + in.getInfo().toString());
				System.out.println("++++++++++++" + in.getDetail().toString());
			}
		}
		else
		{
			System.out.println(result.getStatus());
		}
	}
}

/**
 * 
 * HTTP请求返回状态
 *@Team 
 *@version   V1.0 
 *@date  2014-7-12 下午12:44:37
 */
class Result {
	private boolean isSuccess;
	private String location;
	private String content;
	
	public Result() {}
	public Result(boolean isSuccess, String location, String content) {
		super();
		this.isSuccess = isSuccess;
		this.location = location;
		this.content = content;
	}
	
	public boolean isSuccess() {
		return isSuccess;
	}
	public void setSuccess(boolean isSuccess) {
		this.isSuccess = isSuccess;
	}
	public String getLocation() {
		return location;
	}
	public void setLocation(String location) {
		this.location = location;
	}
	public String getContent() {
		return content;
	}
	public void setContent(String content) {
		this.content = content;
	}
}