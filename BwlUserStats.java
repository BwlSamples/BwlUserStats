/**
 * BwlUserStats
 * 
 * By using Blueworks Live's REST API this application will get information
 * about user activities for a given time period and writes the results into 
 * csv files for further analysis.
 * 
 * @author Martin Westphal, westphal@de.ibm.com
 * @version 1.2
 */
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;

import javax.xml.bind.DatatypeConverter;

import org.apache.commons.io.FileUtils;
import org.apache.wink.json4j.JSONArray;
import org.apache.wink.json4j.JSONException;
import org.apache.wink.json4j.JSONObject;

/**
 * 
 * Compile:
 *    javac -cp .;commons-io-2.4.jar;wink-json4j-1.3.0.jar BwlUserStats.java
 * 
 * Run it:
 *    java -cp .;commons-io-2.4.jar;wink-json4j-1.3.0.jar BwlUserStats <user> <password> <account> 
 * 
 */
public class BwlUserStats {

    // --- The Blueworks Live server info and login
    private final static String REST_API_SERVER = "https://www.blueworkslive.com";
    private static String REST_API_USERNAME = "";
    private static String REST_API_PASSWORD = "";
    private static String REST_API_ACCOUNT_NAME = "";

    // --- Date formats
    private static SimpleDateFormat DATE_INPUTFORMAT = new SimpleDateFormat("yyyy-MM-dd");
    private static SimpleDateFormat DATE_CSVFORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    //private static SimpleDateFormat DATE_PRINTFORMAT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static SimpleDateFormat DATE_ISO8601 =new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    // --- Configuration
    private static String PATH_OUTPUT = "./userstats";
    private static String FILE_LOGINS = "logins.txt";
    private static String FILE_COMMENTS = "comments.txt";
    private static String FILE_UPDATES = "updates.txt";
    private static String FILE_VIEWS = "views.txt";
    //private static String TIME_START = "2015-01-01";
    //private static String TIME_END = "2015-07-31";
    private static Date today = Calendar.getInstance().getTime();
    private static String TIME_END = DATE_INPUTFORMAT.format(today);
    private static String TIME_START = DATE_INPUTFORMAT.format(addDays(today, -99)); //last 100 days
    
    private static boolean DATA_LOGIN = true;
    private static boolean DATA_COMMENT = true;
    private static boolean DATA_UPDATE = true;
    private static boolean DATA_VIEW = true;
    
    // --- Usage
    private static String USAGE = "Usage: BwlUserStats <user> <password> <account> [optional_arguments]\n"
    		+ "Optional arguments:\n"
    		+ "  -h          This help message\n"
    		+ "  -d <path>   Directory to store csv files, default="+PATH_OUTPUT+"\n"
    		+ "  -s <date>   Start date (YYYY-MM-DD), default(100 days)="+TIME_START+"\n"
    		+ "  -e <date>   End date, default(today)="+TIME_END+"\n"
    		+ "  -sl         Skip login data\n"
    		+ "  -sc         Skip comment data\n"
    		+ "  -su         Skip update data\n"
    		+ "  -sv         Skip view data\n"
    		;

    public static void main(String[] args) {
    	int i = 3;
    	Date start = null, end = null;
    	String arg;
    	if (args.length < i) printErrorAndExit("missing command line arguments, 3 arguments required");
    	REST_API_USERNAME = args[0];
    	REST_API_PASSWORD = args[1];
    	REST_API_ACCOUNT_NAME = args[2];
        
    	while (i < args.length && args[i].startsWith("-")) {
            arg = args[i++];
    		if (arg.equals("-h")) printErrorAndExit("");
    		else if (arg.equals("-sl")) { DATA_LOGIN = false; }
    		else if (arg.equals("-sc")) { DATA_COMMENT = false; }
    		else if (arg.equals("-su")) { DATA_UPDATE = false; }
    		else if (arg.equals("-sv")) { DATA_VIEW = false; }
    		else if (arg.equals("-d")) {
                if (i < args.length) PATH_OUTPUT = args[i++];
                else printErrorAndExit("option -d requires a path"); 
            }
    		else if (arg.equals("-s")) {
                if (i < args.length) TIME_START = args[i++];
                else printErrorAndExit("option -s requires a date-time"); 
            }
    		else if (arg.equals("-e")) {
                if (i < args.length) TIME_END = args[i++];
                else printErrorAndExit("option -e requires a date-time"); 
            }
    		else  {
    			printErrorAndExit("unknown command line option "+arg);
            }
    	}
    	
    	try {
			start = DATE_INPUTFORMAT.parse(TIME_START);
			end = DATE_INPUTFORMAT.parse(TIME_END);
		} catch (ParseException e1) {
			e1.printStackTrace();
			printErrorAndExit("could not parse given start or end date");
		}
    	
    	System.out.println("User statistics for Blueworks Live account "+REST_API_ACCOUNT_NAME+" requested by user "+REST_API_USERNAME);
    	System.out.println("Will store files in directory: " + PATH_OUTPUT);
    	System.out.println("Period: " + DATE_INPUTFORMAT.format(start) + " ... " + DATE_INPUTFORMAT.format(end));
    	System.out.println("------------------------------------------------------------------------------");
    	

        try {
        	int totalCountLogins = 0;
        	int totalCountComments = 0;
        	int totalCountUpdates = 0;
        	int totalCountViews = 0;
        	Date tmpstart = start;
        	PrintWriter pwLogins=null, pwComments=null, pwUpdates=null, pwViews=null;
        	
        	FileUtils.forceMkdir(new File(PATH_OUTPUT));
        	if (DATA_LOGIN) {
        		pwLogins = new PrintWriter(new File(PATH_OUTPUT,FILE_LOGINS));
        		pwLogins.println ("Time,EndTime,Type,User");
        	}
        	if (DATA_COMMENT) {
        		pwComments = new PrintWriter(new File(PATH_OUTPUT,FILE_COMMENTS));
        		pwComments.println ("Time,Space,Name,Type,Activity,User,IsReply,Category");
        	}
        	if (DATA_UPDATE) {
        		pwUpdates = new PrintWriter(new File(PATH_OUTPUT,FILE_UPDATES));
        		pwUpdates.println ("Time,Space,Name,Type,User");
        	}
        	if (DATA_VIEW) {
        		pwViews = new PrintWriter(new File(PATH_OUTPUT,FILE_VIEWS));
        		pwViews.println ("Time,Space,Name,Type,User");
        	}
        	
        	while (tmpstart.compareTo(end)<=0) {
        		InputStream restApiStream;
        		Date tmpend = addDays(tmpstart, 20);
        		if (tmpend.compareTo(end)>0) {tmpend = end;}
        		System.out.println("Retrieving info for " + DATE_INPUTFORMAT.format(tmpstart) + " ... " + DATE_INPUTFORMAT.format(tmpend));

        		// --- Login Records ---
            	if (DATA_LOGIN) {
            		int count = 0;
            		restApiStream = getActivityData("LOGINS",tmpstart,tmpend);
            		try {
            			count = processLoginData(restApiStream,pwLogins);
            		} finally {
            			restApiStream.close();
            			System.out.println(" => " + count + " login records found");
            		}
            		totalCountLogins += count;
            	}

        		// --- Comment Records ---
            	if (DATA_COMMENT) {
            		int count = 0;
            		restApiStream = getActivityData("COMMENTS",tmpstart,tmpend);
            		try {
            			count = processCommentData(restApiStream,pwComments);
            		} finally {
            			restApiStream.close();
                    	System.out.println(" => " + count + " comment records found");
            		}
            		totalCountComments += count;
            	}

        		// --- Comment Records ---
            	if (DATA_UPDATE) {
            		int count = 0;
            		restApiStream = getActivityData("ITEMS_CHANGED",tmpstart,tmpend);
            		try {
            			count = processUpdateData(restApiStream,pwUpdates);
            		} finally {
            			restApiStream.close();
                    	System.out.println(" => " + count + " update records found");
            		}
            		totalCountUpdates += count;
            	}

        		// --- Comment Records ---
            	if (DATA_VIEW) {
            		int count = 0;
            		restApiStream = getActivityData("ITEMS_VIEWED",tmpstart,tmpend);
            		try {
            			count = processViewData(restApiStream,pwViews);
            		} finally {
            			restApiStream.close();
                    	System.out.println(" => " + count + " view records found");
            		}
            		totalCountViews += count;
            	}

            	tmpstart = addDays(tmpend, 1);
        	}
			System.out.println("------------------------------------------------------------------------------");
        	if (DATA_LOGIN) {
        		pwLogins.flush(); pwLogins.close ();
    			System.out.println("Found "+totalCountLogins+" login records and stored in "+FILE_LOGINS);
        	}
        	if (DATA_COMMENT) {
        		pwComments.flush(); pwComments.close ();
    			System.out.println("Found "+totalCountComments+" comment records and stored in "+FILE_COMMENTS);
        	}
        	if (DATA_UPDATE) {
        		pwUpdates.flush(); pwUpdates.close ();
    			System.out.println("Found "+totalCountUpdates+" update records and stored in "+FILE_UPDATES);
        	}
        	if (DATA_VIEW) {
        		pwViews.flush(); pwViews.close ();
    			System.out.println("Found "+totalCountViews+" view records and stored in "+FILE_VIEWS);
        	}
			System.out.println("DONE");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    public static Date addDays(Date date, int days)
    {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.DATE, days); 
        return cal.getTime();
    }

    /**
     * Call this method to print out an error message during command line parsing,
     * together with the USAGE information and exit.
     * Use an empty message to get USAGE only.
     * 
     * @param message the error message to print
     */
    private static void printErrorAndExit (String message) {
        if (message.length() > 0) System.err.println("ERROR: "+message);
        System.err.println(USAGE);
        System.exit(1);
    }
    
    /**
     * Generic call of the API resource "activity".
     * 
     * @param type such as LOGINS, COMMENTS, ...
     * @param start start date as yyyy-MM-dd 
     * @param end end date as yyyy-MM-dd
     */
    private static InputStream getActivityData (String type, Date start, Date end) throws IOException {

		StringBuilder appListUrlBuilder = new StringBuilder(REST_API_SERVER + "/scr/api/activity");
		appListUrlBuilder.append("?account=").append(REST_API_ACCOUNT_NAME);
		appListUrlBuilder.append("&type=").append(type);
		appListUrlBuilder.append("&startDate=").append(DATE_INPUTFORMAT.format(start)+"T00:00:00.000-00:00");
		appListUrlBuilder.append("&endDate=").append(DATE_INPUTFORMAT.format(end)+"T23:59:59.999-00:00");
		
		HttpURLConnection restApiURLConnection = getRestApiConnection(appListUrlBuilder.toString());
		if (restApiURLConnection.getResponseCode() != HttpURLConnection.HTTP_OK) {
			System.err.println("Error calling the Blueworks Live REST API: " + restApiURLConnection.getResponseMessage());
			System.exit(1);
		}

		// Process the JSON result.
		InputStream restApiStream = restApiURLConnection.getInputStream();
    	
    	return restApiStream;
    }

    /**
     * Process the login information as retrieved from an API call via getActivityData ().
     * 
     * @param restApiStream InputStream as received from getActivityData ()
     * @param pw a PrintWriter to send the output to 
     */
    private static int processLoginData (InputStream restApiStream, PrintWriter pw) throws JSONException, ParseException  {
    	int count = 0;
		JSONObject appListResult = new JSONObject(restApiStream);
		//System.out.println(appListResult.toString(2));
    
		JSONArray records = (JSONArray) appListResult.get("records");
		for (Object obj : records) {
			count++;
			JSONObject record = (JSONObject)obj;
			//String message = record.getString("message");
			String time = record.getString("time");
			//String timeStamp = record.getString("timeStamp");
			String endTime = "";
			if (record.containsKey("endTime")) endTime = record.getString("endTime");  // might not exist if user is still logged in
			String type = record.getString("type"); // SSO, SESSION_TIMEOUT, CLIENT_TIMEOUT, USER, USER_LOGIN
			String user = record.getString("user");
    	
			Date dateTime = DATE_ISO8601.parse(time);
			if (endTime == "") {
				//System.out.println("RECORD("+count+"): "+DATE_PRINTFORMAT.format(dateTime)+" "+type+" "+user);
				pw.println (DATE_CSVFORMAT.format(dateTime)+",,"+type+","+user);
			}
			else {
				Date dateEndTime = DATE_ISO8601.parse(endTime);
				//System.out.println("RECORD("+count+"): "+DATE_PRINTFORMAT.format(dateTime)+" ... "+DATE_PRINTFORMAT.format(dateEndTime)+" "+type+" "+user);
				pw.println (DATE_CSVFORMAT.format(dateTime)+","+DATE_CSVFORMAT.format(dateEndTime)+","+type+","+user);
			}
		}
		return count;
    }
    /**
     * Process the comments information as retrieved from an API call via getActivityData ().
     * 
     * @param restApiStream InputStream as received from getActivityData ()
     * @param pw a PrintWriter to send the output to 
     */
    private static int processCommentData (InputStream restApiStream, PrintWriter pw) throws JSONException, ParseException  {
    	int count = 0;
		JSONObject appListResult = new JSONObject(restApiStream);
		//System.out.println(appListResult.toString(2));
    
		JSONArray records = (JSONArray) appListResult.get("records");
		for (Object obj : records) {
			count++;
			JSONObject record = (JSONObject)obj;
			//System.out.println(">  "+record.toString(2));
			//String message = record.getString("message");
			String timeStamp = record.getString("timeStamp");
			String spaceName = record.getString("spaceName");
			//String spaceId = record.getString("spaceId");
			String type = record.has("type")?record.getString("type"):""; // PROCESS_COMMENT_ADDED, PROCESS_ITEM_CHANGED, DECISION_COMMENT_ADDED, DECISION_ITEM_CHANGED, ..?
			//String itemType = record.has("itemType")?record.getString("itemType"):""; // PROCESS, DECISION_DIAGRAM (missing for deleted comments)
			String name = "";
			if (type.startsWith("PROCESS")) {
				name = record.has("processName")?record.getString("processName"):"";
			}
			else if (type.startsWith("DECISION")) { 
				name = record.has("decisionDiagramName")?record.getString("decisionDiagramName"):"";
			}
			if (record.has("subType")) {
				type += "."+record.getString("subType");
			}
			String activityType = record.getString("activityType"); // process, linked process, milestone, activity, decision, sub-decision
			String activityName = record.getString("activityName"); // = processName/decisionDiagramName if activityType="process"/"decision"
			String user = record.getString("user"); 
			String isReply = record.has("isReply")?record.getString("isReply"):""; // true, false
			isReply = record.has("isReply ")?record.getString("isReply "):isReply; // true, false
    	
			Date dateTime = DATE_ISO8601.parse(timeStamp);

			//System.out.println("RECORD("+count+"): "+DATE_CSVFORMAT.format(dateTime)+","+spaceName+","+name+","+activityType+","+activityName+","+user+","+isReply);
            pw.println (DATE_CSVFORMAT.format(dateTime)+","+spaceName+","+name+","+activityType+","+activityName+","+user+","+isReply+","+type);
		}
		return count;
    }
    /**
     * Process the updates information as retrieved from an API call via getActivityData ().
     * 
     * @param restApiStream InputStream as received from getActivityData ()
     * @param pw a PrintWriter to send the output to 
     */
    private static int processUpdateData (InputStream restApiStream, PrintWriter pw) throws JSONException, ParseException  {
    	int count = 0;
		JSONObject appListResult = new JSONObject(restApiStream);
		//System.out.println(appListResult.toString(2));
    
		JSONArray records = (JSONArray) appListResult.get("records");
		for (Object obj : records) {
			count++;
			JSONObject record = (JSONObject)obj;
			//String message = record.getString("message");
			String timeStamp = record.getString("timeStamp");
			String spaceName = record.getString("spaceName");
			String processName = "";
			if (record.containsKey("processName")) processName = record.getString("processName");
			//String spaceId = record.getString("spaceId");
			String type = record.getString("type"); // PROCESS_CREATED, PROCESS_PROPERTY_CHANGED, PROCESS_ITEM_CHANGED, PROCESS_SNAPSHOT_TAKEN, SPACE_USER_CHANGED, ...
			String user = record.getString("user");
			// --- and many others depending on type ---
    	
			Date dateTime = DATE_ISO8601.parse(timeStamp);

			//System.out.println("RECORD("+count+"): "+DATE_CSVFORMAT.format(dateTime)+","+spaceName+","+type+","+user);
            pw.println (DATE_CSVFORMAT.format(dateTime)+","+spaceName+","+processName+","+type+","+user);
		}
		return count;
    }
    /**
     * Process the view information as retrieved from an API call via getActivityData ().
     * 
     * @param restApiStream InputStream as received from getActivityData ()
     * @param pw a PrintWriter to send the output to 
     */
    private static int processViewData (InputStream restApiStream, PrintWriter pw) throws JSONException, ParseException  {
    	int count = 0;
		JSONObject appListResult = new JSONObject(restApiStream);
		//System.out.println(appListResult.toString(2));
    
		JSONArray records = (JSONArray) appListResult.get("records");
		for (Object obj : records) {
			count++;
			JSONObject record = (JSONObject)obj;
			//String message = record.getString("message");
			//String time = record.getString("time");
			String itemName = record.getString("itemName");
			String timeStamp = record.getString("timeStamp");
			String spaceName = record.getString("spaceName");
			//String subType = record.getString("subType");
			String itemType = record.getString("itemType"); // process, space, decision, policy
			//String spaceId = record.getString("spaceId");
			//String itemId = record.getString("itemId");
			//String type = record.getString("type"); // ITEM_VIEWED
			String user = record.getString("user");
			//String licenseType = record.getString("licenseType"); // EDITOR, ...
    	
			Date dateTime = DATE_ISO8601.parse(timeStamp);

			//System.out.println("RECORD("+count+"): "+DATE_CSVFORMAT.format(dateTime)+","+spaceName+","+itemName+","+itemType+","+user);
            pw.println (DATE_CSVFORMAT.format(dateTime)+","+spaceName+","+itemName+","+itemType+","+user);
		}
		return count;
    }
    
    /**
     * Set up the connection to a REST API including handling the Basic Authentication request headers that must be
     * present on every API call.
     * 
     * @param apiCall The URL string indicating the api call and parameters.
     * @return the open connection
     */
    public static HttpURLConnection getRestApiConnection(String apiCall) throws IOException {

        // Call the provided api on the Blueworks Live server
        URL restApiUrl = new URL(apiCall);
        HttpURLConnection restApiURLConnection = (HttpURLConnection) restApiUrl.openConnection();

        // Add the HTTP Basic authentication header which should be present on every API call.
        addAuthenticationHeader(restApiURLConnection);

        return restApiURLConnection;
    }

    /**
     * Add the HTTP Basic authentication header which should be present on every API call.
     * 
     * @param restApiURLConnection The open connection to the REST API.
     */
    private static void addAuthenticationHeader(HttpURLConnection restApiURLConnection) {
        String userPwd = REST_API_USERNAME + ":" + REST_API_PASSWORD;
        String encoded = DatatypeConverter.printBase64Binary(userPwd.getBytes());
        restApiURLConnection.setRequestProperty("Authorization", "Basic " + encoded);
    }
}
