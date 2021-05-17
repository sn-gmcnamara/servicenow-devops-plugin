package io.jenkins.plugins.utils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import javax.xml.bind.DatatypeConverter;

import hudson.ProxyConfiguration;
import net.sf.json.JSONObject;

public final class CommUtils {
    private CommUtils() {}
    private static Charset charSet = StandardCharsets.UTF_8;
    private static String contentType = "application/json; charset=" + charSet.name();
    private static int connectTimeout = 5000;
    public static JSONObject call(String method, String urlString, JSONObject params, String data, String username, String password, boolean debug) {

        if (params == null)
            printDebug("call", new String[]{"method","urlString","params","data"}, new String[]{method,urlString,"",data}, debug);
        else
            printDebug("call", new String[]{"method","urlString","params","data"}, new String[]{method,urlString,params.toString(),data}, debug);

        JSONObject jsonResult = null;
        try {
            switch (method) {
                case "GET":
                    jsonResult = _send(urlString, params, data, username, password, debug, DevOpsConstants.REST_GET_METHOD.toString());
                    break;
                case "POST":
                    jsonResult = _send(urlString, params, data, username, password, debug, DevOpsConstants.REST_POST_METHOD.toString());
                    break;
                case "PUT":
                    jsonResult = _send(urlString, params, data, username, password, debug, DevOpsConstants.REST_PUT_METHOD.toString());
                    break;
                default:
                    printDebug("call", new String[]{"message"}, new String[]{"Invalid method name"}, debug);
                    break;
            }
            return jsonResult;

        }  catch (MalformedURLException e) {
            printDebug("call", new String[]{"MalformedURLException"}, new String[]{e.getMessage()}, debug);
            return null;
        } catch (IllegalArgumentException e) {
            printDebug("call", new String[]{"IllegalArgumentException"}, new String[]{e.getMessage()}, debug);
            return null;
        } catch (IOException e) {
            printDebug("call", new String[]{"IOException"}, new String[]{e.getMessage()}, debug);
            return getErrorMessage("IOException: "+e.getMessage());
        } catch (Exception e) {
            printDebug("call", new String[]{"Exception"}, new String[]{e.getMessage()}, debug);
            return null;
        }
    }
    private static JSONObject getErrorMessage(String message) {
		JSONObject resultJSON = new JSONObject();
		resultJSON.put(DevOpsConstants.COMMON_RESULT_FAILURE.toString(), message);
		return resultJSON;
	}
    
    private static JSONObject _send(String urlString, JSONObject params, String data, String username, String password, boolean debug, String method) throws IOException, MalformedURLException, IllegalArgumentException, Exception {
    	JSONObject jsonResult = null;
        URL url = new URL(_appendParams(urlString, params, debug)); 
        if (!url.getProtocol().startsWith("http")) 
            throw new IllegalArgumentException("Not an http(s) url: " + url);
        ProxyConfiguration pc = ProxyConfiguration.load();
        HttpURLConnection conn;
        if (pc != null) 
            conn = (HttpURLConnection) ProxyConfiguration.open(url);
        else
            conn = (HttpURLConnection) url.openConnection();
        byte[] message = (username+":"+password).getBytes(charSet);
        String encoded = DatatypeConverter.printBase64Binary(message);
        conn.setRequestProperty("Authorization", "Basic "+encoded);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setConnectTimeout(connectTimeout);
        conn.setRequestMethod(method);
        if(method.equals(DevOpsConstants.REST_POST_METHOD.toString()) || method.equals(DevOpsConstants.REST_PUT_METHOD.toString())) {
        	conn.setDoOutput(true);
        	OutputStream os = conn.getOutputStream();
        	os.write(data.getBytes(charSet));
        	os.close();
        }
        jsonResult = _readResponse(conn, debug);
        return jsonResult;
    }
    
    private static String _appendParams(String urlString, JSONObject params, boolean debug) {
        printDebug("_appendParams", null, null, debug);
        if (params != null) {
            StringBuffer sb = new StringBuffer(urlString);
            sb.append("?");
            Iterator<String> keys = params.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String val = params.getString(key);
                try {
                    String query = String.format(key+"=%s", URLEncoder.encode(val, charSet.name()));
                    sb.append(query);
                    if (keys.hasNext())
                        sb.append("&");
                } catch (UnsupportedEncodingException e) {
                    printDebug("_appendParams", new String[]{"exception"}, new String[]{e.getMessage()}, debug);
                }
            }
            urlString = sb.toString();
        }
        printDebug("_appendParams", new String[]{"urlString"}, new String[]{urlString}, debug);
        return urlString;
    }

    private static JSONObject _readResponse(HttpURLConnection conn, boolean debug) throws IOException {
        printDebug("_readResponse", null, null, debug);
        JSONObject jsonResult = null;
        InputStream in = null;
        
        // for some SUCCESS cases, the response code is 201 from app-devops. 
		if (conn.getResponseCode() > 299)  // we may use the condition "conn.getResponseCode != 200" in regular cases.
			in = conn.getErrorStream();
        else 
	        in = new BufferedInputStream(conn.getInputStream());
		
        String result = org.apache.commons.io.IOUtils.toString(in, charSet);
        in.close();
        conn.disconnect();
        if (result != null && !result.isEmpty()) 
            jsonResult = JSONObject.fromObject(result);
        if (jsonResult != null)
            printDebug("_readResponse", new String[]{"jsonResult"}, new String[]{jsonResult.toString()}, debug);
        return jsonResult;
    }
    
    private static void printDebug(String methodName, String[] variables, String[] values, boolean debug) {
		GenericUtils.printDebug(CommUtils.class.getName(), methodName, variables, values, debug);
    }
}