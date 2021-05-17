package io.jenkins.plugins.model;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.cps.nodes.StepStartNode;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.FlowScanningUtils;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import hudson.EnvVars;
import hudson.Util;
import hudson.XmlFile;
import hudson.model.AbstractProject;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Job;
import hudson.model.Queue;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.ScheduleResult;
import hudson.scm.SCM;
import hudson.triggers.SCMTrigger;
import io.jenkins.plugins.DevOpsRootAction;
import io.jenkins.plugins.DevOpsRunStatusAction;
import io.jenkins.plugins.config.DevOpsConfiguration;
import io.jenkins.plugins.config.DevOpsJobProperty;
import io.jenkins.plugins.pipeline.steps.DevOpsPipelineMapStep;
import io.jenkins.plugins.pipeline.steps.executions.DevOpsPipelineChangeStepExecution;
import io.jenkins.plugins.utils.CommUtils;
import io.jenkins.plugins.utils.DevOpsConstants;
import io.jenkins.plugins.utils.GenericUtils;
import jenkins.model.Jenkins;
import jenkins.triggers.SCMTriggerItem;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;

public class DevOpsModel {

	public final Pattern urlPatt;
	private boolean queueJobs;

	public DevOpsModel() {
		this.urlPatt = Pattern.compile(
				"^(https?):\\/\\/[-a-zA-Z0-9+&@#\\/%?=~_|!:,.;]*[-a-zA-Z0-9+&@#\\/%=~_|]");
	}

	public boolean isDebug() {
		return DevOpsConfiguration.get().isDebug();
	}

	private void printDebug(String methodName, String[] variables, String[] values) {
		GenericUtils
				.printDebug(DevOpsModel.class.getName(), methodName, variables, values,
						isDebug());
	}

	public boolean isQueueJobs() {
		return queueJobs;
	}

	public void setQueueJobs(boolean queue) {
		this.queueJobs = queue;
	}

	public boolean isApproved(String result) {
		boolean b = false;
		printDebug("isApproved", new String[]{"result"}, new String[]{result});
		try {
			JSONObject jsonObject = JSONObject.fromObject(result);
			if (jsonObject
					.containsKey(DevOpsConstants.CALLBACK_RESULT_ATTR.toString())) {
				result = jsonObject
						.getString(DevOpsConstants.CALLBACK_RESULT_ATTR.toString());

				if (result.equals(DevOpsConstants.CALLBACK_RESULT_SUCCESS.toString())) {
					b = true;
				} else {
					b = false;
				}
			}
		} catch (Exception e) {
			printDebug("isApproved", new String[]{"exception"},
					new String[]{e.getMessage()});
		}
		return b;
	}

	public boolean isCanceled(String result) {
		boolean b = false;
		printDebug("isCanceled", new String[]{"result"}, new String[]{result});
		try {
			JSONObject jsonObject = JSONObject.fromObject(result);
			if (jsonObject
					.containsKey(DevOpsConstants.CALLBACK_RESULT_ATTR.toString())) {
				result = jsonObject
						.getString(DevOpsConstants.CALLBACK_RESULT_ATTR.toString());
				if (result.equals(DevOpsConstants.CALLBACK_RESULT_CANCELED.toString())) {
					b = true;
				} else {
					b = false;
				}
			}
		} catch (Exception e) {
			printDebug("isCanceled", new String[]{"exception"},
					new String[]{e.getMessage()});
		}
		return b;
	}

	public boolean isCommFailure(String result) {
		boolean b = false;
		printDebug("isCommFailure", new String[]{"result"}, new String[]{result});
		try {
			JSONObject jsonObject = JSONObject.fromObject(result);
			if (jsonObject
					.containsKey(DevOpsConstants.CALLBACK_RESULT_ATTR.toString())) {
				result = jsonObject
						.getString(DevOpsConstants.CALLBACK_RESULT_ATTR.toString());
				if (result.equals(DevOpsConstants.CALLBACK_RESULT_COMM_FAILURE.toString())) {
					b = true;
				} else {
					b = false;
				}
			}
		} catch (Exception e) {
			printDebug("isCommFailure", new String[]{"exception"},
					new String[]{e.getMessage()});
		}
		return b;
	}

	// from handleFreestyle (or anywhere that doesn't have a runId associated)
	public DevOpsPipelineInfo checkIsTracking(Queue.Item item) {
		printDebug("checkIsTracking(item)", null, null);
		if (item == null)
			return new DevOpsPipelineInfo(false);
		if (!(item.task instanceof Job<?, ?>))
			return new DevOpsPipelineInfo(false);
		Job<?, ?> job = (Job<?, ?>) item.task;
		// check if global prop and supported job type
		if (!checkIsValid(job))
			return new DevOpsPipelineInfo(false);

		if (GenericUtils.isDevOpsConfigurationEnabled() && !GenericUtils.isDevOpsConfigurationValid())
			return new DevOpsPipelineInfo(false, true, DevOpsConstants.FAILURE_REASON_INVALID_CONFIGURATION_UI.toString());

		String jobUrl = job.getAbsoluteUrl();
		String jobName = job.getFullName();
		// check on endpoint
		printDebug("checkIsTracking(item)", new String[]{"tracking"}, new String[]{"true"});
		return isTrackingEndpoint(jobUrl, jobName, job.getPronoun(), null, false);
	}

	// from onStarted (or anywhere that has a runId associated)
	public DevOpsPipelineInfo checkIsTracking(Job<?, ?> job, String runId, String branchName) {
		printDebug("checkIsTracking(run)", new String[]{"runId","branchName"}, new String[]{runId,branchName});
		if (job == null)
			return new DevOpsPipelineInfo(false);
		// check if global prop and supported job type
		if (!checkIsValid(job))
			return new DevOpsPipelineInfo(false);

		if (GenericUtils.isDevOpsConfigurationEnabled() && !GenericUtils.isDevOpsConfigurationValid())
			return new DevOpsPipelineInfo(false, true, DevOpsConstants.FAILURE_REASON_INVALID_CONFIGURATION_UI.toString());


		String jobUrl = job.getAbsoluteUrl();
		String jobName = job.getFullName();
		// check on cache
		if (isTrackingCache(jobName, runId))
			return new DevOpsPipelineInfo(true);
		// check on endpoint
		return isTrackingEndpoint(jobUrl, jobName, job.getPronoun(), branchName,
				GenericUtils.isMultiBranch(job));
	}

	public DevOpsPipelineInfo getPipelineInfo(Job<?, ?> job, String runId){
		if (job == null)
			return new DevOpsPipelineInfo(false);
		// check if global prop and supported job type
		if (!checkIsValid(job))
			return new DevOpsPipelineInfo(false);

		if (GenericUtils.isDevOpsConfigurationEnabled() && !GenericUtils.isDevOpsConfigurationValid())
			return new DevOpsPipelineInfo(false, true, DevOpsConstants.FAILURE_REASON_INVALID_CONFIGURATION_UI.toString());

		String jobName = job.getFullName();
		String key = getTrackingKey(jobName, runId);
		printDebug("getPipelineInfo", new String[]{"jobName","key"}, new String[]{jobName,key});
		return DevOpsRootAction.getSnPipelineInfo(key);
	}

	public boolean checkIsTrackingCache(Job<?, ?> job, String runId) {
		printDebug("checkIsTrackingCache", new String[]{"runId"}, new String[]{runId});
		if (job == null)
			return false;
		// check if global prop and supported job type
		if (!checkIsValid(job))
			return false;
		String jobName = job.getFullName();
		// check on cache
		return isTrackingCache(jobName, runId);
	}

	public boolean checkIsValid(Job<?, ?> job) {
		printDebug("checkIsValid", null, null);
		if (job == null)
			return false;
		// check if the job pronoun is the one we support
		String pronoun = job.getPronoun();
		if (!(pronoun.equalsIgnoreCase(DevOpsConstants.PIPELINE_PRONOUN.toString()) ||
				pronoun.equalsIgnoreCase(DevOpsConstants.BITBUCKET_MULTI_BRANCH_PIPELINE_PRONOUN.toString()) ||
				pronoun.equalsIgnoreCase(DevOpsConstants.FREESTYLE_PRONOUN.toString()) ||
				pronoun.equalsIgnoreCase(DevOpsConstants.FREESTYLE_MAVEN_PRONOUN.toString())))
			return false;
		if (!GenericUtils.isDevOpsConfigurationEnabled())
			return false;
		printDebug("checkIsValid", new String[]{"valid"}, new String[]{"true"});
		return true;
	}

	public String getTrackingKey(String jobName, String runId) {
		printDebug("getTrackingKey", new String[]{"runId","jobName"}, new String[]{runId,jobName});
		if (jobName == null)
			return null;
		return jobName + DevOpsConstants.TRACKING_KEY_SEPARATOR.toString() + runId;
	}

	public void addToPipelineInfoCache(String jobName, String runId, DevOpsPipelineInfo pipelineInfo) {
		printDebug("addToPipelineInfoCache", new String[]{"runId","jobName"}, new String[]{runId,jobName});
		String key = getTrackingKey(jobName, runId);
		if (key == null)
			return;
		if (pipelineInfo == null)
			return;
		DevOpsRootAction.setSnPipelineInfo(key, pipelineInfo);
	}

	public void removeFromPipelineInfoCache(String jobName, String runId) {
		printDebug("removeFromPipelineInfoCache", new String[]{"runId","jobName"}, new String[]{runId,jobName});
		String key = getTrackingKey(jobName, runId);
		if (key == null)
			return;
		DevOpsRootAction.removeSnPipelineInfo(key);
	}

	public void addToTrackingCache(String jobName, String runId, DevOpsPipelineInfo pipelineInfo) {
		printDebug("addToTrackingCache", new String[]{"runId","jobName"}, new String[]{runId,jobName});
		String key = getTrackingKey(jobName, runId);
		printDebug("addToTrackingCache", new String[]{"key"}, new String[]{key});
		if (key == null)
			return;
		DevOpsRootAction.setTrackedJob(key);
	}

	public void removeFromTrackingCache(String jobName, String runId) {
		printDebug("removeFromTrackingCache", new String[]{"runId","jobName"}, new String[]{runId,jobName});
		String key = getTrackingKey(jobName, runId);
		if (key == null)
			return;
		DevOpsRootAction.removeTrackedJob(key);
	}

	public boolean isTrackingCache(String jobName, String runId) {
		printDebug("isTrackingCache", new String[]{"runId","jobName"}, new String[]{runId,jobName});
		String key = getTrackingKey(jobName, runId);
		if (key == null)
			return false;
		Boolean tracking = DevOpsRootAction.getTrackedJob(key);
		if (tracking == null)
			return false;
		printDebug("isTrackingCache", new String[]{"tracking"},new String[]{String.valueOf(tracking.booleanValue())});
		return tracking.booleanValue();
	}

	public DevOpsPipelineInfo isTrackingEndpoint(String jobUrl, String jobName, String pronoun, String branchName, boolean isMultiBranch) {
		printDebug("isTrackingEndpoint", new String[]{"jobUrl","jobName","pronoun","branchName","isMultiBranch"}, new String[]{jobUrl,jobName,pronoun,branchName,String.valueOf(isMultiBranch)});
		JSONObject params = new JSONObject();
		String result = null;
		DevOpsConfiguration devopsConfig = GenericUtils.getDevOpsConfiguration();
		params.put(DevOpsConstants.TOOL_ID_ATTR.toString(), devopsConfig.getToolId());
		params.put("url", jobUrl);
		params.put("name", jobName);
		params.put("pronoun", pronoun);
		if (branchName != null)
			params.put("branchName", branchName);
		params.put("isMultiBranch", isMultiBranch);
		JSONObject infoAPIResponse = CommUtils.call(DevOpsConstants.REST_GET_METHOD.toString(),
				devopsConfig.getTrackingUrl(), params, null,
				devopsConfig.getUser(), devopsConfig.getPwd(), isDebug());
		result = GenericUtils.parseResponseResult(infoAPIResponse,
					DevOpsConstants.TRACKING_RESPONSE_ATTR.toString());
		printDebug("isTrackingEndpoint", new String[]{DevOpsConstants.TRACKING_RESPONSE_ATTR.toString()},new String[]{result});
		
		if (result != null) {
			if (result.equalsIgnoreCase("true")) {
				DevOpsPipelineInfo pipelineInfo = new DevOpsPipelineInfo(true);
				JSONObject resultObj = infoAPIResponse.getJSONObject(DevOpsConstants.COMMON_RESPONSE_RESULT.toString());
				if (resultObj.containsKey(DevOpsConstants.TEST_INFO_RESPONSE.toString())) {
					pipelineInfo.setTestInfo(resultObj.getJSONObject(DevOpsConstants.TEST_INFO_RESPONSE.toString()));
				}
				return pipelineInfo;
			}
			else if (result.equalsIgnoreCase("false")) {
				return new DevOpsPipelineInfo(false);
			}
			else if (result.contains(DevOpsConstants.COMMON_RESULT_FAILURE.toString())) {
				if (result.contains(DevOpsConstants.FAILURE_REASON_CONN_REFUSED.toString()))
					return new DevOpsPipelineInfo(false, true, DevOpsConstants.FAILURE_REASON_CONN_REFUSED_UI.toString());
				else if (result.contains(DevOpsConstants.FAILURE_REASON_USER_NOAUTH.toString()))
					return new DevOpsPipelineInfo(false, true, DevOpsConstants.FAILURE_REASON_USER_NOAUTH_UI.toString());
				else if (pronoun != null && 
						 result.contains(DevOpsConstants.FAILURE_REASON_PIPELINE_DETAILS_NOT_FOUND.toString()) && 
						 (pronoun.equalsIgnoreCase(DevOpsConstants.FREESTYLE_PRONOUN.toString()) || pronoun.equalsIgnoreCase(DevOpsConstants.FREESTYLE_MAVEN_PRONOUN.toString())))
					return new DevOpsPipelineInfo(false);	
			}
		}
		return new DevOpsPipelineInfo(false, true, DevOpsConstants.FAILURE_REASON_GENERIC_UI.toString());

	}

	public static class DevOpsPipelineInfo {
		private boolean track;
		private JSONObject testInfo;
		private boolean unreacheable;
		private String errorMessage;

		public DevOpsPipelineInfo(boolean track) {
			this.track = track;
		}

		public DevOpsPipelineInfo(boolean track, JSONObject testInfo) {
			this.track = track;
			this.testInfo = testInfo;
		}

		public DevOpsPipelineInfo(boolean track, boolean unreacheable, String errorMessage) {
			this.track = track;
			this.unreacheable = unreacheable;
			this.errorMessage = errorMessage;
		}

		public boolean isUnreacheable() {
			return unreacheable;
		}

		public void setUnreacheable(boolean unreacheable) {
			this.unreacheable = unreacheable;
		}

		public boolean isTrack() {
			return track;
		}

		public void setTrack(boolean track) {
			this.track = track;
		}

		public JSONObject getTestInfo() {
			return testInfo;
		}

		public void setTestInfo(JSONObject testInfo) {
			this.testInfo = testInfo;
		}

		public void setErrorMessage(String message) {
			this.errorMessage = message;
		}

		public String getErrorMessage() {
			return errorMessage;
		}

		public String toString() { return "track: " + this.track + ", unreacheable: " + this.unreacheable; }
	}

	// When parallel pipelines are triggered and Job run #1 is waiting for approval,
	// Jenkins will not automatically schedule Job run #2 once #1 finishes.
	// This method is called from the build step, and does the manual scheduling.
	public boolean scheduleNextJob(Run<?, ?> run, Job<?, ?> job, int quietPeriod) {
		printDebug("scheduleNextJob", null, null);
		List<Cause> causes = run.getCauses();
		if (causes.size() > 1) {
			List<Cause> _causes = new ArrayList<>();
			for (int i = 1; i < causes.size(); i++) {
				Cause c = causes.get(i);
				if (c instanceof Cause.UpstreamCause)
					_causes.add(c);
			}
			CauseAction cAction = new CauseAction(_causes);
			Jenkins jenkins = Jenkins.getInstanceOrNull();
			if (jenkins != null) {
				Queue queue = jenkins.getQueue();
				Queue.Task project = jenkins.getItemByFullName(job.getFullName(),
						AbstractProject.class);
				if (queue != null && project != null && cAction != null) {
					ScheduleResult sResult =
							queue.schedule2(project, quietPeriod, cAction);
					return sResult.isCreated();
				}
			}
		}
		return false;
	}

	// called from dispatcher
	public String getJobId(Queue.Item item, Job<?, ?> job) {
		printDebug("getJobId", null, null);
		String queueId = String.valueOf(item.getId());
		String jobName = job.getUrl();
		return queueId + "/" + jobName;
	}

	// (freestyle only) called from runListener.setupEnvironment, after runListener.onStarted
	public String getJobId(Run<?, ?> run, Job<?, ?> job) {
		printDebug("getJobId", null, null);
		// queueId is used in case of change control for freestyle
		String queueId = String.valueOf(run.getQueueId());
		String jobName = job.getUrl();
		return queueId + "/" + jobName;
	}

	// get from 'callbackContent' map
	public String getCallbackResult(String jobId) {
		printDebug("getCallbackResult", new String[]{"jobId"}, new String[]{jobId});
		return DevOpsRootAction.getCallbackContent(jobId);
	}

	// called from build step
	public String removeCallbackResult(String jobId) {
		printDebug("removeCallbackResult", new String[]{"jobId"}, new String[]{jobId});
		return DevOpsRootAction.removeCallbackContent(jobId);
	}

	// called from build step
	public String removeCallbackToken(String jobId) {
		printDebug("removeCallbackToken", new String[]{"jobId"}, new String[]{jobId});
		return DevOpsRootAction.removeCallbackToken(jobId);
	}

	// get from 'jobs' map
	public String getToken(String jobId) {
		printDebug("getToken", new String[]{"jobId"}, new String[]{jobId});
		return DevOpsRootAction.getToken(jobId);
	}

	// new token
	public String getNewToken(String pronoun) {
		printDebug("getNewToken", new String[]{"pronoun"}, new String[]{pronoun});
		String token = null;
		if (pronoun != null) {
			if (pronoun.equalsIgnoreCase(DevOpsConstants.FREESTYLE_PRONOUN.toString()) ||
			    pronoun.equalsIgnoreCase(DevOpsConstants.FREESTYLE_MAVEN_PRONOUN.toString()))
				token = DevOpsConstants.FREESTYLE_CALLBACK_URL_IDENTIFIER.toString() +
				        DevOpsConstants.CALLBACK_TOKEN_SEPARATOR.toString() +
				        java.util.UUID.randomUUID().toString();
			else if (pronoun
					         .equalsIgnoreCase(DevOpsConstants.PIPELINE_PRONOUN.toString()) ||
			         pronoun.equalsIgnoreCase(
					         DevOpsConstants.BITBUCKET_MULTI_BRANCH_PIPELINE_PRONOUN
							         .toString()))
				token = DevOpsConstants.PIPELINE_CALLBACK_URL_IDENTIFIER.toString() +
				        DevOpsConstants.CALLBACK_TOKEN_SEPARATOR.toString() +
				        java.util.UUID.randomUUID().toString();
		}
		return token;
	}

	// Jenkins singleton
	public String getJenkinsUrl() {
		printDebug("getJenkinsUrl", null, null);
		String url = null;
		try {
			Jenkins jenkins = Jenkins.getInstanceOrNull();
			if (jenkins != null)
				url = jenkins.getRootUrl();
		} catch (IllegalStateException e) {
			printDebug("getJenkinsUrl", new String[]{"exception"},
					new String[]{e.getMessage()});
		}
		return url;
	}


	// get from 'webhooks' map
	public boolean isWaiting(String token) {
		printDebug("isWaiting", null, null);
		String _jobId = DevOpsRootAction.getJobId(token);
		if (_jobId != null)
			return true;
		return false;
	}

	public boolean checkUrlValid(String url) {
		printDebug("checkUrlValid", new String[]{"url"}, new String[]{url});
		Matcher m = urlPatt.matcher(url);
		return m.matches();
	}

	private String getCallbackUrl(String token, String jenkinsUrl)
			throws URISyntaxException {
		printDebug("getCallbackUrl", new String[]{"token", "jenkinsUrl"},
				new String[]{token, jenkinsUrl});
		java.net.URI baseUri = new java.net.URI(jenkinsUrl);
		java.net.URI relative = new java.net.URI(
				DevOpsConstants.CALLBACK_URL_IDENTIFIER.toString() + "/" + token);
		java.net.URI path = baseUri.resolve(relative);
		return path.toString();
	}

	public String replaceLast(String string, String substring, String replacement) {
		int index = string.lastIndexOf(substring);
		if (index == -1)
			return string;
		return string.substring(0, index) + replacement +
		       string.substring(index + substring.length());
	}

	public String sendBuildAndToken(String token, String jenkinsUrl, String buildUrl,
	                                String jobUrl, String jobName, String stageName,
	                                DevOpsPipelineNode rootNode, Boolean isMultiBranch, String branchName, Boolean isChangeClose) {
		printDebug("sendBuildAndToken", null, null);
		JSONObject params = new JSONObject();
		JSONObject data = new JSONObject();
		String result = null;
		DevOpsConfiguration devopsConfig = GenericUtils.getDevOpsConfiguration();

		params.put(DevOpsConstants.TOOL_ID_ATTR.toString(), devopsConfig.getToolId());
		params.put(DevOpsConstants.TOOL_TYPE_ATTR.toString(),
				DevOpsConstants.TOOL_TYPE.toString());
		try {
			String callbackUrl = getCallbackUrl(token, jenkinsUrl);
			data.put(DevOpsConstants.CALLBACK_URL_ATTR.toString(), callbackUrl);
			data.put(DevOpsConstants.JOB_URL_ATTR.toString(), jobUrl);
			data.put(DevOpsConstants.BUILD_URL_ATTR.toString(), buildUrl);
			data.put(DevOpsConstants.IS_MULTI_BRANCH_ATTR.toString(),
					Boolean.toString(isMultiBranch));
			data.put(DevOpsConstants.SCM_BRANCH_NAME.toString(), branchName);

			if (stageName != null && !stageName.isEmpty()) {
				data.put(DevOpsConstants.JOB_NAME_ATTR.toString(),
						jobName + DevOpsConstants.JOB_STAGE_SEPARATOR.toString() +
						stageName);
				data.put(DevOpsConstants.JOB_URL_ATTR.toString(), replaceLast(jobUrl, "/",
						DevOpsConstants.JOB_STAGE_SEPARATOR.toString() + stageName +
						"/"));//jobUrl+DevOpsConstants.JOB_STAGE_SEPARATOR.toString()+stageName);
			} else
				data.put(DevOpsConstants.JOB_NAME_ATTR.toString(), jobName);

			if (isChangeClose && null != rootNode)
				data.put(DevOpsConstants.PARENT_BUILD_URL_ATTR.toString(),
						jenkinsUrl+rootNode.getExecutionUrl() + "wfapi/describe");

			addRootParams(rootNode, data, jobName, jobUrl, DevOpsConstants.REST_PUT_METHOD);

			result = GenericUtils.parseResponseResult(
					CommUtils.call(DevOpsConstants.REST_PUT_METHOD.toString(),
							devopsConfig.getChangeControlUrl() + "/" + token, params, data.toString(),
							devopsConfig.getUser(), devopsConfig.getPwd(), isDebug()),
					DevOpsConstants.COMMON_RESPONSE_CHANGE_CTRL.toString());
		} catch (Exception e) {
			printDebug("sendBuildAndToken", new String[]{"exception"},
					new String[]{e.getMessage()});
		}
		return result;
	}

	public String sendIsUnderChgControl(String jobUrl, String jobName,
	                                    String stageName, DevOpsPipelineNode rootNode, Boolean isMultiBranch,
	                                    String branchName) {
		printDebug("sendIsUnderChgControl", null, null);
		JSONObject params = new JSONObject();
		String result = null;
		DevOpsConfiguration devopsConfig = GenericUtils.getDevOpsConfiguration();

		params.put(DevOpsConstants.TOOL_ID_ATTR.toString(), devopsConfig.getToolId());
		params.put(DevOpsConstants.TOOL_TYPE_ATTR.toString(),
				DevOpsConstants.TOOL_TYPE.toString());
		params.put(DevOpsConstants.JOB_URL_ATTR.toString(), jobUrl);
		params.put(DevOpsConstants.IS_MULTI_BRANCH_ATTR.toString(),
				Boolean.toString(isMultiBranch));
		params.put(DevOpsConstants.SCM_BRANCH_NAME.toString(), branchName);

		if (stageName != null && !stageName.isEmpty()) {

			params.put(DevOpsConstants.JOB_NAME_ATTR.toString(),
					jobName + DevOpsConstants.JOB_STAGE_SEPARATOR.toString() + stageName);
			params.put(DevOpsConstants.JOB_URL_ATTR.toString(), replaceLast(jobUrl, "/",
					DevOpsConstants.JOB_STAGE_SEPARATOR.toString() + stageName + "/"));
		} else
			params.put(DevOpsConstants.JOB_NAME_ATTR.toString(), jobName);

		addRootParams(rootNode, params, jobName, jobUrl, DevOpsConstants.REST_GET_METHOD);

		try {
			result = GenericUtils
					.parseResponseResult(
							CommUtils.call(DevOpsConstants.REST_GET_METHOD.toString(), devopsConfig.getChangeControlUrl(), params, null,
									devopsConfig.getUser(), devopsConfig.getPwd(), isDebug()),
							DevOpsConstants.COMMON_RESPONSE_CHANGE_CTRL.toString());
		} catch (Exception e) {
			printDebug("sendIsUnderChgControl", new String[]{"exception"},
					new String[]{e.getMessage()});
		}
		return result;
	}

	public String sendJobAndCallbackUrl(String token, String jobUrl, String jobName,
	                                    String stageName, DevOpsPipelineNode rootNode, String jenkinsUrl,
	                                    String endpointUrl, String user, String pwd,
	                                    String tool, JSONObject jobDetails,
	                                    Boolean isMultiBranch, String branchName, String changeRequestDetails) {
		printDebug("sendJobAndCallbackUrl", null, null);
		JSONObject params = new JSONObject();
		JSONObject data = new JSONObject();
		String result = null;
		params.put(DevOpsConstants.TOOL_ID_ATTR.toString(), tool);
		params.put(DevOpsConstants.TOOL_TYPE_ATTR.toString(),
				DevOpsConstants.TOOL_TYPE.toString());
		try {
			String callbackUrl = getCallbackUrl(token, jenkinsUrl);
			data.put(DevOpsConstants.CALLBACK_URL_ATTR.toString(), callbackUrl);
			data.put(DevOpsConstants.JOB_URL_ATTR.toString(), jobUrl);
			data.put(DevOpsConstants.IS_MULTI_BRANCH_ATTR.toString(),
					Boolean.toString(isMultiBranch));
			data.put(DevOpsConstants.SCM_BRANCH_NAME.toString(), branchName);

			if (stageName != null && !stageName.isEmpty()) {
				data.put(DevOpsConstants.JOB_NAME_ATTR.toString(),
						jobName + DevOpsConstants.JOB_STAGE_SEPARATOR.toString() +
						stageName);
				data.put(DevOpsConstants.JOB_URL_ATTR.toString(), replaceLast(jobUrl, "/",
						DevOpsConstants.JOB_STAGE_SEPARATOR.toString() + stageName +
						"/"));
			} else
				data.put(DevOpsConstants.JOB_NAME_ATTR.toString(), jobName);

			addRootParams(rootNode, data, jobName, jobUrl, DevOpsConstants.REST_POST_METHOD);

			data.put(DevOpsConstants.JOB_DETAILS_ATTR.toString(), jobDetails);

			if (GenericUtils.isNotEmpty(changeRequestDetails)) {
				JSONObject crAttrJSON = JSONObject.fromObject(changeRequestDetails);
				data.put(DevOpsConstants.CR_ATTRS.toString(), crAttrJSON);
			}

			result = GenericUtils
					.parseResponseResult(
							CommUtils.call(DevOpsConstants.REST_POST_METHOD.toString(), endpointUrl, params,
									data.toString(), user, pwd, isDebug()),
							DevOpsConstants.COMMON_RESPONSE_CHANGE_CTRL.toString());
		} catch(JSONException e){
			printDebug("sendJobAndCallbackUrl", new String[]{"JSONException"},
					new String[]{e.getMessage()});
			JSONObject errorObj = new JSONObject();
			String errorMessage = "Failed to parse changeRequestDetails json." + e.getMessage();
			errorObj.put(DevOpsConstants.COMMON_RESULT_FAILURE.toString(), errorMessage);
			result = errorObj.toString();
		} catch (Exception e) {
			printDebug("sendJobAndCallbackUrl", new String[]{"exception"},
					new String[]{e.getMessage()});
		}
		return result;
	}

	public DevOpsJobProperty getJobProperty(Job<?, ?> job) {
		printDebug("getJobProperty", null, null);
		DevOpsJobProperty jobProperty = job.getProperty(DevOpsJobProperty.class);
		if (jobProperty == null)
			jobProperty = new DevOpsJobProperty();
		return jobProperty;
	}

	public String sendUpdateMapping(String jobUrl, String jobName, String stageName, DevOpsPipelineNode rootNode,
	                                String stepSysId, Boolean isMultiBranch, String branchName) {
		printDebug("sendUpdateMapping", null, null);
		JSONObject params = new JSONObject();
		JSONObject data = new JSONObject();
		String result = null;
		DevOpsConfiguration devopsConfig = GenericUtils.getDevOpsConfiguration();

		params.put(DevOpsConstants.TOOL_ID_ATTR.toString(), devopsConfig.getToolId());
		params.put(DevOpsConstants.TOOL_TYPE_ATTR.toString(),
				DevOpsConstants.TOOL_TYPE.toString());
		data.put(DevOpsConstants.JOB_URL_ATTR.toString(), jobUrl);
		data.put(DevOpsConstants.IS_MULTI_BRANCH_ATTR.toString(), Boolean.toString(isMultiBranch));
		data.put(DevOpsConstants.SCM_BRANCH_NAME.toString(), branchName);
		if (stageName != null && !stageName.isEmpty()) {
			data.put(DevOpsConstants.JOB_NAME_ATTR.toString(),
					jobName + DevOpsConstants.JOB_STAGE_SEPARATOR.toString() + stageName);
			data.put(DevOpsConstants.JOB_URL_ATTR.toString(), replaceLast(jobUrl, "/",
					DevOpsConstants.JOB_STAGE_SEPARATOR.toString() + stageName + "/"));
		} else
			data.put(DevOpsConstants.JOB_NAME_ATTR.toString(), jobName);

		if (!GenericUtils.isEmpty(stepSysId))
			data.put(DevOpsConstants.STEP_SYSID_ATTR.toString(), stepSysId);

		addRootParams(rootNode, data, jobName, jobUrl, DevOpsConstants.REST_POST_METHOD);

		try {
			result = GenericUtils.parseResponseResult(
					CommUtils.call(DevOpsConstants.REST_POST_METHOD.toString(), devopsConfig.getMappingUrl(), params,
							data.toString(), devopsConfig.getUser(), devopsConfig.getPwd(), isDebug()),
					DevOpsConstants.STEP_MAPPING_RESPONSE_ATTR.toString());
		} catch (Exception e) {
			printDebug("sendIsMappingValid", new String[]{"exception"},
					new String[]{e.getMessage()});
		}
		return result;
	}

	private void addRootParams(DevOpsPipelineNode rootNode, JSONObject params, String jobName, String jobUrl, DevOpsConstants callMethod) {
		if (rootNode != null) {
			String rootStageName = rootNode.getName();
			if (rootStageName != null && !rootStageName.isEmpty()) {
				params.put(DevOpsConstants.JOB_PARENT_STAGE_NAME.toString(),
						jobName + DevOpsConstants.JOB_STAGE_SEPARATOR.toString() + rootStageName);
				params.put(DevOpsConstants.JOB_PARENT_STAGE_URL.toString(),
						replaceLast(jobUrl, "/", DevOpsConstants.JOB_STAGE_SEPARATOR.toString() + rootStageName + "/"));
				// avoid adding entire rootNode data to query params for GET method
				if ((callMethod.equals(DevOpsConstants.REST_POST_METHOD) || callMethod.equals(DevOpsConstants.REST_PUT_METHOD)))
					params.put(DevOpsConstants.JOB_PARENT_STAGE_DATA.toString(), getRootJSONObject(rootNode));

			}
		}
	}

	private JSONObject getRootJSONObject(DevOpsPipelineNode rootNode) {
		JSONObject rootJSONObj = new JSONObject();
		rootJSONObj.put("id",rootNode.getId());
		rootJSONObj.put("name", rootNode.getName());
		rootJSONObj.put("parentId",rootNode.getParentId());
		rootJSONObj.put("upstreamStageName",rootNode.getUpstreamStageName());
		rootJSONObj.put("upstreamTaskExecutionURL",rootNode.getUpstreamTaskExecURL());
		return rootJSONObj;
	}


	public CauseOfBlockage getWaitingBlockage(String message) {
		final String _message = message;
		return new CauseOfBlockage() {
			@Override
			public String getShortDescription() {
				return _message;
			}
		};
	}

	public String registerFreestyleAndNotify(Queue.Item item, Job<?, ?> job, String token,
	                                         String jobId, String jobUrl, String jobName,
	                                         String jenkinsUrl) {
		printDebug("registerFreestyleAndNotify", null, null);
		String result = null;
		try {
			JSONObject jobDetails = getJobDetailsForFreestyle(item, job, jenkinsUrl);

			// conditions in which the job should be cancelled (and item removed from the queue):
			// 1 when trigger=scm, if there's no difference between revisions
			//Example: duplicate triggers, and/or race condition where Polling action overlaps with callback receival and release of the job to start running
			/*if (jobDetails.getString(DevOpsConstants.TRIGGER_TYPE_ATTR.toString()).equals("scm")) {
				JSONObject scmChanges = jobDetails.getJSONObject(DevOpsConstants.SCM_CHANGES_ATTR.toString());
				if (scmChanges.containsKey("cancel") && scmChanges.getBoolean("cancel")) {
					output.put("error", true);
					return output;
				}
			}*/

			DevOpsConfiguration devopsConfig = GenericUtils.getDevOpsConfiguration();

			result = sendJobAndCallbackUrl(token,
					jobUrl, jobName, null, null,
					jenkinsUrl, devopsConfig.getChangeControlUrl(), devopsConfig.getUser(),
					devopsConfig.getPwd(), devopsConfig.getToolId(),
					jobDetails, GenericUtils.isMultiBranch((job)), null, getJobProperty(job).getChangeRequestDetails());
			/* result: null (if call failed), "unknown", "true", "false" */
			if (result != null) {
				if (result.equalsIgnoreCase(
						DevOpsConstants.COMMON_RESPONSE_VALUE_TRUE.toString())) {
					DevOpsRootAction.registerWebhook(token, jobId);
					DevOpsRootAction.registerJob(jobId, token);
				}
			}

		} catch (Exception e) {
			//GenericUtils.printConsoleLog(listener, "SUCCESS: Register Artifact request was successful.");
			printDebug("registerAndFreestyleNotify", new String[]{"exception"},
					new String[]{e.getMessage()});
		}
		return result;
	}

	public JSONObject getJobDetailsForFreestyle(Queue.Item item, Job<?, ?> controlledJob,
	                                            String jenkinsUrl)
			throws InterruptedException {
		printDebug("getJobDetailsForFreestyle", null, null);
		List<Cause> causes = item.getCauses();
		return _getJobDetails(causes, controlledJob, jenkinsUrl);
	}

	public JSONObject getJobDetailsForPipeline(Run<?, ?> run, Job<?, ?> controlledJob,
	                                           String jenkinsUrl)
			throws InterruptedException {
		printDebug("getJobDetailsForPipeline", null, null);
		JSONObject json = new JSONObject();
		if (run != null) {
			DevOpsRunStatusAction action = run.getAction(DevOpsRunStatusAction.class);
			if (action != null) {
				DevOpsRunStatusModel statusModel = action.getModel();
				if (statusModel != null) {
					DevOpsRunStatusStageModel stageModel = statusModel.getStageModel();
					if (stageModel != null) {

						String upstreamTaskExecutionURL =null;
						if (GenericUtils.isEmpty(stageModel.getParentStageName())) {

							upstreamTaskExecutionURL= stageModel.getUpstreamTaskExecutionUrl();
						} else {
							DevOpsPipelineNode rootNode = getRootNode(run, stageModel.getName());
							upstreamTaskExecutionURL= rootNode.getUpstreamTaskExecURL();
						}

						if (upstreamTaskExecutionURL != null &&
						    !upstreamTaskExecutionURL.isEmpty()) {
							json.put(DevOpsConstants.MESSAGE_ATTR.toString(),
									"Started by " + upstreamTaskExecutionURL);
							json.put(DevOpsConstants.TRIGGER_TYPE_ATTR.toString(),
									"upstream");
							json.put(DevOpsConstants.UPSTREAM_BUILD_URL_ATTR.toString(),
									upstreamTaskExecutionURL);
						} else {
							List<Cause> causes = run.getCauses();
							json = _getJobDetails(causes, controlledJob, jenkinsUrl);
						}
					}
				}
			} else {
				List<Cause> causes = run.getCauses();
				json = _getJobDetails(causes, controlledJob, jenkinsUrl);
			}
		}
		return json;
	}

	private JSONObject _getJobDetails(List<Cause> causes, Job<?, ?> job,
	                                  String jenkinsUrl) {
		printDebug("_getJobDetails", null, null);
		JSONObject json = new JSONObject();
		for (Cause cause : causes) {
			json.put(DevOpsConstants.MESSAGE_ATTR.toString(),
					cause.getShortDescription());
			if (cause instanceof Cause.UserIdCause) {
				Cause.UserIdCause userCause = (Cause.UserIdCause) cause;
				json.put(DevOpsConstants.TRIGGER_TYPE_ATTR.toString(), "user");
				json.put(DevOpsConstants.USERNAME_ATTR.toString(),
						userCause.getUserName());
				json.put(DevOpsConstants.LAST_BUILD_URL_ATTR.toString(), "");
				Run<?, ?> lastRun = job.getLastBuild();
				if (lastRun != null) {
					String lastBuildUrl = jenkinsUrl + lastRun.getUrl();
					Matcher m = urlPatt.matcher(lastBuildUrl);
					if (m.matches())
						json.put(DevOpsConstants.LAST_BUILD_URL_ATTR.toString(),
								lastBuildUrl);
				}
				printDebug("_getJobDetails", new String[]{"cause is UserIdCause"},
						new String[]{userCause.getShortDescription()});
				break;
			} else if (cause instanceof Cause.UpstreamCause) {
				Cause.UpstreamCause upstreamCause = (Cause.UpstreamCause) cause;
				json.put(DevOpsConstants.TRIGGER_TYPE_ATTR.toString(), "upstream");

				String upstreamBuildUrl = jenkinsUrl + upstreamCause.getUpstreamUrl() +
				                          upstreamCause.getUpstreamBuild() + "/";
				Matcher m = urlPatt.matcher(upstreamBuildUrl);
				if (m.matches())
					json.put(DevOpsConstants.UPSTREAM_BUILD_URL_ATTR.toString(),
							upstreamBuildUrl);

				printDebug("_getJobDetails", new String[]{"cause is UpstreamCause"},
						new String[]{upstreamCause.getShortDescription()});
				break;
			} else if (cause instanceof SCMTrigger.SCMTriggerCause) {
				SCMTrigger.SCMTriggerCause scmCause = (SCMTrigger.SCMTriggerCause) cause;
				printDebug("_getJobDetails", new String[]{"cause is SCMTriggerCause"},
						new String[]{scmCause.getShortDescription()});
				json.put(DevOpsConstants.TRIGGER_TYPE_ATTR.toString(), "scm");
				SCMTriggerItem tItem = SCMTriggerItem.SCMTriggerItems
						.asSCMTriggerItem(job); // tTtem = instance of Project
				if (tItem != null) {
					SCMTrigger trigger = tItem.getSCMTrigger();

					if (trigger != null) {
						File log = trigger.getLogFile();
						if (log != null) {
							if (log.canRead()) {
								try {
									String fContent = Util.loadFile(log);
									json.put(DevOpsConstants.SCM_LOG_ATTR.toString(),
											fContent);
								} catch (IOException e1) {
									e1.printStackTrace();
								}
							}
						}
					}
					@SuppressWarnings("unchecked")
					Collection<SCM> scms = (Collection<SCM>) tItem.getSCMs();
					for (SCM scm : scms) {
						if (scm.getClass().getName()
								.equals(DevOpsConstants.GIT_PLUGIN_SCM_CLASS
										.toString())) {
							json.put(DevOpsConstants.SCM_TYPE_ATTR.toString(), "git");
							// if scmChanges is empty, means that:
							// 		- there was multiple branches tracked, and/or
							//		- the local repo was not yet initialized
							// if scmChanges.cancel == true, will result in the job being cancelled (removed from queue)
							//JSONObject scmChanges = gitModel.getScmChanges(job, item);
							JSONObject scmChanges = new JSONObject();
							json.put(DevOpsConstants.SCM_CHANGES_ATTR.toString(),
									scmChanges);
							break;
						}
					}
				}
				break; // break needed to avoid running the job twice, for the odd case where we receive 2 SCMTriggerCause
			} else {
				printDebug("_getJobDetails",
						new String[]{"cause is " + cause.getClass().getSimpleName()},
						new String[]{cause.getShortDescription()});
				json.put(DevOpsConstants.TRIGGER_TYPE_ATTR.toString(), "default");
			}
		}
		return json;
	}

	public String registerPipelineAndNotify(Run<?, ?> run, Job<?, ?> controlledJob,
	                                        String token, String jobUrl, String jobName,
	                                        String stageName, String jenkinsUrl,
	                                        DevOpsPipelineChangeStepExecution stepExecution) {
		printDebug("registerPipelineAndNotify", null, null);
		String result = null;
		try {
			JSONObject jobDetails =
					getJobDetailsForPipeline(run, controlledJob, jenkinsUrl);

			StepContext ctx = stepExecution.getContext();

			EnvVars vars = null;
			FlowNode fn = null;
			try {
				fn = ctx.get(FlowNode.class);
				vars = ctx.get(EnvVars.class);
			} catch (IOException | InterruptedException e) {
				e.printStackTrace();
			}

			String changeRequestDetails = stepExecution.getStep().getChangeRequestDetails();
			if(GenericUtils.isNotEmpty(changeRequestDetails) && vars!=null)
					changeRequestDetails = vars.expand(changeRequestDetails);

			DevOpsPipelineNode rootNode = getRootNode(run, stageName);

			String buildUrl = getBuildUrl(fn, vars, run, jenkinsUrl, stageName, rootNode);
			jobDetails.put(DevOpsConstants.BUILD_URL_ATTR.toString(),buildUrl);
			DevOpsConfiguration devopsConfig = GenericUtils.getDevOpsConfiguration();
			result = sendJobAndCallbackUrl(token,
					jobUrl,
					jobName,
					stageName, rootNode,
					jenkinsUrl,
					devopsConfig.getChangeControlUrl(),
					devopsConfig.getUser(),
					devopsConfig.getPwd(),
					devopsConfig.getToolId(),
					jobDetails, GenericUtils.isMultiBranch(controlledJob),
					vars != null ? vars.get("BRANCH_NAME") : null,
					changeRequestDetails);
			// only register webhook if able to post to SN endpoint
			if (null != result && !result.contains(DevOpsConstants.COMMON_RESULT_FAILURE.toString())) {
				if (result.equalsIgnoreCase(
						DevOpsConstants.COMMON_RESPONSE_VALUE_TRUE.toString())) {
					stepExecution.setToken(token);
					DevOpsRootAction.registerPipelineWebhook(stepExecution);
				}
			} else {
				String cause = "";
				if(null != result)
					cause = "Cause: "+result;
				printDebug("registerPipelineAndNotify", new String[]{"message"},
						new String[]{"Register change control failed. Response from sendJobAndCallbackUrl(): "+cause});
			}
		} catch (InterruptedException e) {
			printDebug("registerPipelineAndNotify", new String[]{"exception"},
					new String[]{e.getMessage()});
		}
		return result;
	}


	public String getBuildUrl(FlowNode fn, EnvVars vars, Run<?, ?> run, String jenkinsUrl,
							  String stageName, DevOpsPipelineNode rootNode) {
		String buildUrl = "";
		if (fn != null && vars != null && run != null && jenkinsUrl != null) {
			if (stageName != null && !stageName.isEmpty()) {
				DevOpsRunStatusAction action = run.getAction(DevOpsRunStatusAction.class);
				if (action != null) {
					DevOpsRunStatusModel statusModel = action.getModel();
					if (statusModel != null) {
						DevOpsRunStatusStageModel stageModel =
								statusModel.getStageModel();
						if (stageModel != null)
							buildUrl = stageModel.getUrl();
					}
				} else {
					buildUrl = getBuildUrlForStage(fn, vars, run, jenkinsUrl, stageName);
				}
			} else {
				buildUrl = jenkinsUrl + run.getUrl();
			}
		}
		return buildUrl;
	}

	public String getBuildUrlForStage(FlowNode fn, EnvVars vars, Run<?, ?> run,
									  String jenkinsUrl, String stageName) {
		String buildUrl = "";
		// get all enclosing BlockNodes
		// search for labelAction and step name == stage
		// if compare stage names, if they match get the node ID
		// build the url
		if (fn != null && vars != null && run != null && jenkinsUrl != null) {
			// Deprecated: use FlowNode.iterateEnclosingBlocks() (not seem available in this version)
			Iterator<FlowNode> it = FlowScanningUtils.fetchEnclosingBlocks(fn);
			while (it.hasNext()) {
				FlowNode _fn = it.next();
				if (_fn instanceof StepStartNode) {
					boolean isStage =
							((StepStartNode) _fn).getStepName().equalsIgnoreCase("stage");
					String stepId = _fn.getId();
					LabelAction lAction = _fn.getAction(LabelAction.class);
					if (lAction != null && stepId != null && !stepId.isEmpty()) {
						String _stageName = lAction.getDisplayName();
						if (isStage && _stageName.equalsIgnoreCase(stageName)) {
							buildUrl = jenkinsUrl + run.getUrl() + "execution/node/" +
									stepId + "/wfapi/describe";
							break;
						}
					}
				}
			}
		}
		return buildUrl;
	}

	public String getStageNameFromAction(Run<?, ?> run) {
		printDebug("getStageNameFromAction", null, null);
		String stageName = null;
		if (run != null) {
			DevOpsRunStatusAction action = run.getAction(DevOpsRunStatusAction.class);
			if (action != null) {
				DevOpsRunStatusModel statusModel = action.getModel();
				if (statusModel != null) {
					DevOpsRunStatusStageModel stageModel = statusModel.getStageModel();
					if (stageModel != null) {
						stageName = stageModel.getName();
					}
				}
			}
		}
		return stageName;
	}

	public enum PipelineChangeAction {
		ABORT,
		WAIT,
	}

	public static class PipelineChangeResponse {
		PipelineChangeAction action;
		String errorMessage;

		public String getErrorMessage() {
			return errorMessage;
		}

		public void setErrorMessage(String errorMessage) {
			this.errorMessage = errorMessage;
		}


		public PipelineChangeAction getAction() {
			return action;
		}

		public void setAction(PipelineChangeAction action) {
			this.action = action;
		}
	}

	// Called from DevOpsPipelineChangeStepExecution.start()
	public PipelineChangeResponse handlePipeline(Run<?, ?> run, Job<?, ?> controlledJob,
	                                             DevOpsPipelineChangeStepExecution stepExecution) {

		printDebug("handlePipeline", null, null);
		PipelineChangeResponse changeResponse = new PipelineChangeResponse();
		//boolean[] result = new boolean[2]; // 0: shouldAbort, 1: shouldWait
		//resp.setResult(result);
		if (run != null && controlledJob != null) {
			String jobUrl = controlledJob.getAbsoluteUrl();
			String jobName = controlledJob.getName();
			String jenkinsUrl = getJenkinsUrl();

			if (jobUrl != null && jenkinsUrl != null && jobName != null) {
				String stageName = getStageNameFromAction(run);
				DevOpsPipelineNode rootNode = getRootNode(run, stageName);

				StepContext ctx = stepExecution.getContext();
				EnvVars vars = null;
				try {
					vars = ctx.get(EnvVars.class);
				} catch (Exception e) {
					e.printStackTrace();
				}

				// If Job is under change control, register and notify SN with callback URL
				String _result = sendIsUnderChgControl(jobUrl, jobName, stageName, rootNode,
						GenericUtils.isMultiBranch(controlledJob), vars != null ? vars.get(
								"BRANCH_NAME") : null);
				if (_result != null) {
					// Job is under change control
					if (_result.equalsIgnoreCase(
							DevOpsConstants.COMMON_RESPONSE_VALUE_TRUE.toString())) {
						printDebug("handlePipeline",
								new String[]{"message", "jobUrl", "jobName"},
								new String[]{"Job is under change control", jobUrl,
										jobName});
						// Generate a new token
						String token = getNewToken(controlledJob.getPronoun());
						printDebug("handlePipeline", new String[]{"token"},
								new String[]{token});

						// Register the Job callback hook, then notify SN
						_result = registerPipelineAndNotify(run, controlledJob, token,
								jobUrl, jobName, stageName, jenkinsUrl,
								stepExecution);
						if (_result != null) {
							if (_result.equalsIgnoreCase(
									DevOpsConstants.COMMON_RESPONSE_VALUE_TRUE
											.toString())) {
								printDebug("handlePipeline",
										new String[]{"message", "token"},
										new String[]{"Job registered", token});
								//result[1] = true; // shouldWait=true
								changeResponse.setAction(PipelineChangeAction.WAIT);
							} else {
								printDebug("handlePipeline", new String[]{"message"},
										new String[]{
												"Something went wrong when registering the job"});
								//result[0] = true; // shouldAbort=true
								changeResponse.setAction(PipelineChangeAction.ABORT);
								changeResponse.setErrorMessage(_result);
							}
						} else {
							printDebug("handlePipeline", new String[]{"message"},
									new String[]{
											"Something went wrong when calling SN to register the job"});
							//result[0] = true; // shouldAbort=true
							changeResponse.setAction(PipelineChangeAction.ABORT);
						}
					} else if (_result.equalsIgnoreCase(
							DevOpsConstants.COMMON_RESPONSE_VALUE_FALSE.toString())) {
						printDebug("handlePipeline", new String[]{"message"},
								new String[]{"Job is not under change control"});
					} else if (_result.equalsIgnoreCase(
							DevOpsConstants.COMMON_RESPONSE_VALUE_UNKNOWN.toString())) {
						printDebug("handlePipeline", new String[]{"message"},
								new String[]{"Could not find a step for job"});
						//result[0] = true; // shouldAbort=true
						changeResponse.setAction(PipelineChangeAction.ABORT);
					}
				} else {
					printDebug("handlePipeline", new String[]{"message"}, new String[]{
							"Something went wrong when checking if job is under change control"});
					//result[0] = true; // shouldAbort=true
					changeResponse.setAction(PipelineChangeAction.ABORT);
				}
			}
		}

		return changeResponse;
	}

	public String getCommFailureResult() {
		printDebug("getCommFailureResult", null, null);
		JSONObject result = new JSONObject();
		result.put(DevOpsConstants.CALLBACK_RESULT_ATTR.toString(), DevOpsConstants.CALLBACK_RESULT_COMM_FAILURE.toString());
		return result.toString();
	}

	public String getAbortResult() {
		printDebug("getAbortResult", null, null);
		JSONObject result = new JSONObject();
		result.put(DevOpsConstants.CALLBACK_CANCELED_ATTR.toString(), "true");
		return result.toString();
	}

	public void setAbortResultForFreestyle(String jobId, String _result) {
		printDebug("setAbortResultForFreestyle", new String[]{"jobId"},
				new String[]{jobId});
		String result = GenericUtils.isEmpty(_result) ? getAbortResult() : _result;
		if (jobId != null)
			DevOpsRootAction.setCallbackContent(jobId, result);
	}

	public void setAbortResultForFreestyle(String jobId) {
		printDebug("setAbortResultForFreestyle", new String[]{"jobId"},
				new String[]{jobId});
		if (jobId != null)
			DevOpsRootAction.setCallbackContent(jobId, getAbortResult());
	}

	private boolean configHasFreestyleStep(Job<?, ?> job) {
		printDebug("configHasFreestyleStep", null, null);
		try {
			XmlFile xmlFile = job.getConfigFile();
			if (xmlFile.exists()) {
				File file = xmlFile.getFile();
				Document doc = readJobConfig(file);
				if (doc != null) {
					NodeList nList = doc.getElementsByTagName(
							DevOpsConstants.FREESTYLE_STEP_CLASS.toString());
					return nList.getLength() > 0;
				}
			}
		} catch (Exception e) {
			printDebug("configHasFreestyleStep", new String[]{"exception"},
					new String[]{e.getMessage()});
		}
		return false;
	}

	private Document readJobConfig(File xmlFile) {
		printDebug("readJobConfig", null, null);
		if (xmlFile != null) {
			try {
				if (xmlFile.canRead()) {
					DocumentBuilderFactory dbFactory =
							DocumentBuilderFactory.newInstance();
					DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
					Document doc = dBuilder.parse(xmlFile);
					doc.getDocumentElement().normalize();
					if (doc.getDocumentElement().getNodeName() == "project")
						return doc;
				}
			} catch (Exception e) {
				printDebug("readJobConfig", new String[]{"exception"},
						new String[]{e.getMessage()});
			}
		}
		return null;
	}

	private void cancelItem(Queue.Item item) {
		printDebug("cancelItem", null, null);
		Jenkins jenkins = Jenkins.getInstanceOrNull();
		if (jenkins != null)
			jenkins.getQueue().cancel(item);
	}

	// Called from DevOpsQueueTaskDispatcher.canRun()
	public CauseOfBlockage handleFreestyle(Queue.Item item,
	                                       Job<?, ?> job) {
		printDebug("handleFreestyle", null, null);
		if (item != null && job != null) {

			// First step is to check if this Job has already been evaluated and we have a response in the callbackContent hashmap
			String jobId = getJobId(item, job);
			String result = getCallbackResult(jobId);
			if (result != null) {
				printDebug("handleFreestyle", new String[]{"callback result"}, new String[]{result});
				return null;
			}

			// No response yet for this Job
			else {
				printDebug("handleFreestyle", new String[]{"callback result"},
						new String[]{"null"});
				String jobUrl = job.getAbsoluteUrl();
				String jobName = job.getName();
				String jenkinsUrl = getJenkinsUrl();
				if (jobUrl != null && jenkinsUrl != null && jobName != null &&
					jobId != null) {
					String token = getToken(jobId);
					// Job already registered
					if (token != null) {
						printDebug("handleFreestyle", new String[]{"message", "token"},
								new String[]{
										"Job already registered, waiting for callback",
										token});

						// Job is waiting for callback
						if (isWaiting(token))
							return getWaitingBlockage("Job is waiting for approval");
					}
					// Job not registered
					else {
						printDebug("handleFreestyle", new String[]{"message", "token"},
								new String[]{"Job not registered", "null"});
						// Check if job is being tracked
						if (checkIsTracking(item).isTrack()) {
							// If Job is under change control, register and notify SN with callback URL
							String _result = sendIsUnderChgControl(jobUrl, jobName, null, null,
									GenericUtils.isMultiBranch((job)), null);
							if (_result != null) {
								// Job is under change control
								if (_result.equalsIgnoreCase(
										DevOpsConstants.COMMON_RESPONSE_VALUE_TRUE
												.toString())) {
									printDebug("handleFreestyle",
											new String[]{"message", "jobUrl", "jobName"},
											new String[]{"Job is under change control",
													jobUrl, jobName});

									// Generate a new token
									token = getNewToken(job.getPronoun());
									printDebug("handleFreestyle", new String[]{"token"},
											new String[]{token});

									// Register the Job callback hook, then notify SN
									_result = registerFreestyleAndNotify(item, job,
											token, jobId, jobUrl, jobName, jenkinsUrl);
									if (_result != null) {
										// Job registered successfully
										if (_result.equalsIgnoreCase(
												DevOpsConstants.COMMON_RESPONSE_VALUE_TRUE
														.toString())) {
											printDebug("handleFreestyle",
													new String[]{"message", "token"},
													new String[]{"Job registered", token});
											return getWaitingBlockage(
													"Job is waiting for approval");
										}
										// Could not register the Job callback, so there are no webhooks registered
										else {
											printDebug("handleFreestyle",
													new String[]{"message", "_result"},
													new String[]{
															"Something went wrong when registering the job",
															_result});
											if (GenericUtils.isNotEmpty(_result) && _result.contains(DevOpsConstants.COMMON_RESULT_FAILURE.toString())) {
												setAbortResultForFreestyle(jobId, _result);
											}else {
												setAbortResultForFreestyle(jobId);
											}
										}
									}
									// Call to SN failed
									else {
										printDebug("handleFreestyle", new String[]{"message"},
												new String[]{
														"Something when wrong when calling SN to register the job"});
										setAbortResultForFreestyle(jobId);
									}
								}
								// Job is not under change control
								else if (_result.equalsIgnoreCase(
										DevOpsConstants.COMMON_RESPONSE_VALUE_FALSE
												.toString())) {
									printDebug("handleFreestyle",
											new String[]{"message", "jobUrl"},
											new String[]{"Job is not under change control",
													jobUrl});
								} else if (_result.equalsIgnoreCase(
										DevOpsConstants.COMMON_RESPONSE_VALUE_UNKNOWN
												.toString())) {
									printDebug("handleFreestyle", new String[]{"message"},
											new String[]{
													"Job is not associated with any step"});
									setAbortResultForFreestyle(jobId);
								}

							}
							// Failed to check if the Job is under change control
							else {
								printDebug("handleFreestyle", new String[]{"message"},
										new String[]{
												"Something went wrong when checking if job is under change control"});
								setAbortResultForFreestyle(jobId);
							}
						}
					}
				}
			}
		}
		return null;
	}

	// Called from DevOpsPipelineMapStepExecution.run()
	public boolean handleStepMapping(Run<?, ?> run,
	                                 Job<?, ?> controlledJob,
	                                 DevOpsPipelineMapStep devOpsPipelineMapStep, EnvVars vars) {

		boolean result = false;
		if (devOpsPipelineMapStep == null)
			return result;

		String stepSysId = devOpsPipelineMapStep.getStepSysId();

		printDebug("handleStepMapping", new String[]{"stepSysId --"},
				new String[]{stepSysId});

		if (run != null && controlledJob != null) {
			String jobUrl = controlledJob.getAbsoluteUrl();
			String jobName = controlledJob.getName();
			String jenkinsUrl = getJenkinsUrl();

			if (jobUrl != null && jenkinsUrl != null && jobName != null) {
				String stageName = getStageNameFromAction(run);
				DevOpsPipelineNode rootNode = getRootNode(run, stageName);

				// return true if step is already associated to it's root
				if (isStepAssociated(run, stageName)) {
					this.associateStepToNode(run, stageName);
					printDebug("handleStepMapping", new String[] { "message" },
							new String[] { "Step has been associated already" });
					return true;
				}

				String _result = sendUpdateMapping(jobUrl, jobName, stageName, rootNode,
						stepSysId,
						GenericUtils.isMultiBranch(controlledJob),
						vars != null ? vars.get("BRANCH_NAME") : null);
				if (null != _result && !_result.contains(DevOpsConstants.COMMON_RESULT_FAILURE.toString())) {
					// associated successfully
					if (_result.equalsIgnoreCase(DevOpsConstants.COMMON_RESPONSE_VALUE_TRUE.toString())) {
						result = true;
						this.associateStepToNode(run, stageName);
						printDebug("handleStepMapping", new String[] { "message" },
								new String[] { "Step associated successfully" });
					}
					// could not associate for some reason
					else
						printDebug("handleStepMapping", new String[] { "message" },
								new String[] { "Step could not be associated - invalid" });
				} else {
					String cause = "";
					if (null != _result)
						cause = "Cause: " + _result;
					printDebug("handleStepMapping", new String[] { "message" }, new String[] {
							"Something when wrong when calling SN to associate the step. Reason: " + cause });
				}
			}
		}
		return result;
	}

	public DevOpsPipelineNode getRootNode(Run<?, ?> run, String stageName) {
		printDebug("getRootNode", null, null);
		if (run != null) {
			DevOpsRunStatusAction action = run.getAction(DevOpsRunStatusAction.class);
			if (action != null) {
				DevOpsPipelineNode nodeByName = action.getPipelineGraph().getNodeByName(stageName);
				if (null != nodeByName) {
					DevOpsPipelineNode rootNodeRef = nodeByName.getRootNodeRef();
					if (null != rootNodeRef)
						return rootNodeRef;
					return nodeByName;
				}
			}
		}
		return null;
	}

	private boolean isStepAssociated(Run<?, ?> run, String stageName) {
		DevOpsRunStatusAction action = run.getAction(DevOpsRunStatusAction.class);
		if (action != null) {
			return action.getPipelineGraph().isStepAssociated(stageName);
		}
		return false;
	}

	public void associateStepToNode(Run<?, ?> run, String stageName) {
		if (null != run && null != stageName) {
			DevOpsRunStatusAction action = run.getAction(DevOpsRunStatusAction.class);
			if (action != null) {
				action.getPipelineGraph().addStepToNode(stageName);
			}
		}

	}

	public boolean isChangeStepInProgress(Run<?, ?> run) {
		DevOpsPipelineNode currentNode = getCurrentNode(run);
		if (null != currentNode)
			return currentNode.isChangeCtrlInProgress();
		return false;
	}

	public void markChangeStepToProgress(Run<?, ?> run) {
		DevOpsPipelineNode currentNode = getCurrentNode(run);
		if (null != currentNode)
			currentNode.setChangeCtrlInProgress(true);
	}

	public DevOpsPipelineNode getCurrentNode(Run<?, ?> run) {
		DevOpsPipelineNode currentNode = null;
		if (run != null) {
			DevOpsRunStatusAction action = run.getAction(DevOpsRunStatusAction.class);
			if (action != null) {
				DevOpsRunStatusModel statusModel = action.getModel();
				if (statusModel != null) {
					DevOpsRunStatusStageModel stageModel = statusModel.getStageModel();
					if (stageModel != null) {
						currentNode = action.getPipelineGraph().getNodeByName(stageModel.getName());
					}
				}
			}
		}
		return currentNode;
	}

	public String handleArtifactRegistration(Run<?, ?> run, TaskListener listener, String artifactPayload, EnvVars vars) {

		String result = null;
		printDebug("handleArtifactRegistration", new String[] { "artifactPayload --" },
				new String[] { artifactPayload });
		String buildNumber = null, stageName = null, branchName = null, jobUrl = null, jobName = null;

		// TODO - this is being repeated in other method too, see if we can generalise it by an object
		if (vars != null) {
			jobName = vars.get("JOB_NAME");
			buildNumber = vars.get("BUILD_NUMBER");
			stageName = vars.get("STAGE_NAME");

			if (GenericUtils.isNotEmpty(vars.get("GIT_BRANCH")))
				branchName = vars.get("GIT_BRANCH");
			else if (GenericUtils.isNotEmpty(vars.get("BRANCH_NAME")))
				branchName = vars.get("BRANCH_NAME");
			jobUrl = vars.get("JOB_URL");
		} else {
			return result;
		}

		if(GenericUtils.isEmpty(stageName))
			stageName = getStageNameFromAction(run);

		if (GenericUtils.isNotEmpty(stageName)){
			DevOpsPipelineNode rootNode = getRootNode(run, stageName);
			if (rootNode!=null && GenericUtils.isNotEmpty(rootNode.getName()) && !rootNode.getName().equals(stageName)){
				stageName = rootNode.getName();
			}
		}

		result = registerArtifact(listener, artifactPayload, jobName, jobUrl, buildNumber, stageName, branchName,
				GenericUtils.isFreeStyleProject(run));

		return result;

	}

	public String registerArtifact(TaskListener listener, String artifactsPayload, String jobName, String jobUrl,
			String buildNumber, String stageName, String branchName, boolean isFreeStyle) {

		printDebug("registerArtifact", null, null);
		JSONObject queryParams = new JSONObject();
		JSONObject payload = new JSONObject();
		String result = null;
		DevOpsConfiguration devopsConfig = GenericUtils.getDevOpsConfiguration();
		try {
			// If artifact tool Id is available, add this to query param (it's an optional parameter).
			if (devopsConfig.getSnArtifactToolId() != null && devopsConfig.getSnArtifactToolId().length() > 0)
				queryParams.put(DevOpsConstants.TOOL_ID_ATTR.toString(), devopsConfig.getSnArtifactToolId());
			// add orchestration tool id to q-params
			queryParams.put(DevOpsConstants.ORCHESTRATION_TOOL_ID_ATTR.toString(), devopsConfig.getToolId());

			JSONObject artifactsPayloadJSON = JSONObject.fromObject(artifactsPayload);
			if (artifactsPayloadJSON.containsKey(DevOpsConstants.ARTIFACT_ARTIFACTS_ATTR.toString())) {
				JSONArray artifactsJSONArray = artifactsPayloadJSON
						.getJSONArray(DevOpsConstants.ARTIFACT_ARTIFACTS_ATTR.toString());
				payload.put(DevOpsConstants.ARTIFACT_ARTIFACTS_ATTR.toString(), artifactsJSONArray); // artifacts
			}
			// replace stageName from given payload
			if(artifactsPayloadJSON.containsKey(DevOpsConstants.ARTIFACT_STAGE_NAME.toString()))
				stageName = artifactsPayloadJSON.getString(DevOpsConstants.ARTIFACT_STAGE_NAME.toString());

			// replace branchName from given payload
			if(artifactsPayloadJSON.containsKey(DevOpsConstants.ARTIFACT_BRANCH_NAME.toString()))
				branchName = artifactsPayloadJSON.getString(DevOpsConstants.ARTIFACT_BRANCH_NAME.toString());

			//replace buildNumber/taskExecNum from given payload
			if(artifactsPayloadJSON.containsKey(DevOpsConstants.ARTIFACT_TASK_EXEC_NUM.toString()))
				buildNumber = artifactsPayloadJSON.getString(DevOpsConstants.ARTIFACT_TASK_EXEC_NUM.toString());

			if (isFreeStyle)
				payload.put(DevOpsConstants.ARTIFACT_PROJECT_NAME.toString(), jobName); // projectName
			else
				payload.put(DevOpsConstants.ARTIFACT_PIPELINE_NAME.toString(), jobName); // pipelineName

			if(null != buildNumber)
				payload.put(DevOpsConstants.ARTIFACT_TASK_EXEC_NUM.toString(), buildNumber); // buildNumber

			if (null != stageName)
				payload.put(DevOpsConstants.ARTIFACT_STAGE_NAME.toString(), stageName); // stageName

			if(null != branchName)
				payload.put(DevOpsConstants.ARTIFACT_BRANCH_NAME.toString(), branchName); // branchName

			printDebug("registerArtifact", new String[] { "message" },
					new String[] { "Payload: " + payload.toString() });
			GenericUtils.printConsoleLog(listener, "Register artifact payload: "+payload.toString());

			// make a POST call
			JSONObject response = CommUtils.call(DevOpsConstants.REST_POST_METHOD.toString(),
					devopsConfig.getArtifactRegistrationUrl(), queryParams, payload.toString(),
					devopsConfig.getUser(), devopsConfig.getPwd(), isDebug());

			if(null != response) {
				// log the response for user
				GenericUtils.printConsoleLog(listener, "Register artifact on URL " + devopsConfig.getArtifactRegistrationUrl()
				+ " responded with : " + response.toString());
				// validate response
				result = GenericUtils.parseResponseResult(response,
						DevOpsConstants.ARTIFACT_REGISTER_STATUS_ATTR.toString());
			}

		} catch (Exception e) {
			printDebug("registerArtifact", new String[] { "exception" }, new String[] { e.getMessage() });
			GenericUtils.printConsoleLog(listener, "Register artifact request could not be sent due to the exception: "+e.getMessage());
		}

		return result;
	}

	public String handleArtifactCreatePackage(Run<?, ?> run, TaskListener listener, String packageName, String payload, EnvVars vars) {

		String result = null;
		printDebug("handleArtifactCreatePackage", new String[] { "packageName --" }, new String[] { packageName });

		if (run != null) {
			String buildNumber = null, stageName = null, branchName = null, jobUrl = null, jobName = null;

			// TODO - this is being repeated in other method too, see if we can generalise it by an object
			if (vars != null) {
				jobName = vars.get("JOB_NAME");
				buildNumber = vars.get("BUILD_NUMBER");
				stageName = vars.get("STAGE_NAME");

				if (GenericUtils.isNotEmpty(vars.get("GIT_BRANCH")))
					branchName = vars.get("GIT_BRANCH");
				else if (GenericUtils.isNotEmpty(vars.get("BRANCH_NAME")))
					branchName = vars.get("BRANCH_NAME");
				jobUrl = vars.get("JOB_URL");
			} else {
				return result;
			}

			if(GenericUtils.isEmpty(stageName))
				stageName = getStageNameFromAction(run);

			if (GenericUtils.isNotEmpty(stageName)){
				DevOpsPipelineNode rootNode = getRootNode(run, stageName);
				if (rootNode!=null && GenericUtils.isNotEmpty(rootNode.getName()) && !rootNode.getName().equals(stageName)){
					stageName = rootNode.getName();
				}
			}

			result = createArtifactPackage(listener, packageName, payload, jobName, jobUrl, buildNumber, stageName, branchName,
					GenericUtils.isFreeStyleProject(run));
		}

		return result;

	}

	public String createArtifactPackage(TaskListener listener, String artifactName, String artifactsPayload, String jobName, String jobUrl,
			String buildNumber, String stageName, String branchName, boolean isFreeStyle) {

		printDebug("createArtifactPackage", null, null);
		JSONObject queryParams = new JSONObject();
		JSONObject payload = new JSONObject();
		List<JSONObject> artifactsList = new ArrayList<JSONObject>();
		String result = null;
		DevOpsConfiguration devopsConfig = GenericUtils.getDevOpsConfiguration();
		try {
			// If artifact tool Id is available, add this to query param (it's an optional parameter).
			if (devopsConfig.getSnArtifactToolId() != null && devopsConfig.getSnArtifactToolId().length() > 0)
				queryParams.put(DevOpsConstants.TOOL_ID_ATTR.toString(), devopsConfig.getSnArtifactToolId());
			// add orchestration tool id to q-params
			queryParams.put(DevOpsConstants.ORCHESTRATION_TOOL_ID_ATTR.toString(), devopsConfig.getToolId());

			// Prepare Artifacts payload
			payload.put(DevOpsConstants.ARTIFACT_NAME_ATTR.toString(), artifactName); // name
			// Add pipeline/build details to artifacts, if currentBuildInfo flag is set to true
			// sample artifacts payload: artifacts: ” [ { url: “url1”, currentBuildInfo : true}, { “url”: “url2", currentBuildInfo : true } ] ”
			JSONObject artifactsPayloadJSON = JSONObject.fromObject(artifactsPayload);
			if (artifactsPayloadJSON.containsKey(DevOpsConstants.ARTIFACT_ARTIFACTS_ATTR.toString())) {
				JSONArray artifactsJSONArray = artifactsPayloadJSON
						.getJSONArray(DevOpsConstants.ARTIFACT_ARTIFACTS_ATTR.toString());
				for (Object artifactObj : artifactsJSONArray) {
					JSONObject artifactJSON = (JSONObject) artifactObj;
					if (artifactJSON.containsKey(DevOpsConstants.ARTIFACT_CURRENT_BUILD_INFO.toString())
							&& artifactJSON.getString(DevOpsConstants.ARTIFACT_CURRENT_BUILD_INFO.toString())
									.equals(DevOpsConstants.COMMON_RESPONSE_VALUE_TRUE.toString())) {
						// 1. remove the currentBuildInfo param
						artifactJSON.remove(DevOpsConstants.ARTIFACT_CURRENT_BUILD_INFO.toString());
						// 2. add build details
						artifactJSON = addBuildDetails(artifactJSON, jobName, buildNumber, stageName, branchName);
					}
					// add artifactJSON to Array
					artifactsList.add(artifactJSON);
				}
				payload.put(DevOpsConstants.ARTIFACT_ARTIFACTS_ATTR.toString(), artifactsList); // artifacts
			}
			// replace stageName from given payload
			if (artifactsPayloadJSON.containsKey(DevOpsConstants.ARTIFACT_STAGE_NAME.toString()))
				stageName = artifactsPayloadJSON.getString(DevOpsConstants.ARTIFACT_STAGE_NAME.toString());

			// replace branchName from given payload
			if (artifactsPayloadJSON.containsKey(DevOpsConstants.ARTIFACT_BRANCH_NAME.toString()))
				branchName = artifactsPayloadJSON.getString(DevOpsConstants.ARTIFACT_BRANCH_NAME.toString());

			// replace buildNumber/taskExecNum from given payload
			if (artifactsPayloadJSON.containsKey(DevOpsConstants.ARTIFACT_TASK_EXEC_NUM.toString()))
				buildNumber = artifactsPayloadJSON.getString(DevOpsConstants.ARTIFACT_TASK_EXEC_NUM.toString());

			if (isFreeStyle)
				payload.put(DevOpsConstants.ARTIFACT_PROJECT_NAME.toString(), jobName); // projectName
			else
				payload.put(DevOpsConstants.ARTIFACT_PIPELINE_NAME.toString(), jobName); // pipelineName

			if(null != buildNumber)
				payload.put(DevOpsConstants.ARTIFACT_TASK_EXEC_NUM.toString(), buildNumber); // buildNumber

			if(null != stageName)
				payload.put(DevOpsConstants.ARTIFACT_STAGE_NAME.toString(), stageName); // stageName

			if(null != branchName)
				payload.put(DevOpsConstants.ARTIFACT_BRANCH_NAME.toString(), branchName); // branchName

			printDebug("createArtifactPackage", new String[] { "message" },
					new String[] { "Payload: " + payload.toString() });
			GenericUtils.printConsoleLog(listener, "Create Artifact package payload: "+payload.toString());

			// make a POST call
			JSONObject response = CommUtils.call(DevOpsConstants.REST_POST_METHOD.toString(),
					devopsConfig.getArtifactCreatePackageUrl(), queryParams, payload.toString(),
					devopsConfig.getUser(), devopsConfig.getPwd(), isDebug());
			//validate response and assign it to result.
			if (response != null) {
				// log the response for user
				GenericUtils.printConsoleLog(listener, "Create artifact package on URL "
						+ devopsConfig.getArtifactCreatePackageUrl() + " responded with : " + response.toString());

				result = GenericUtils.parseResponseResult(response,
						DevOpsConstants.ARTIFACT_REGISTER_STATUS_ATTR.toString());
			}

		} catch (Exception e) {
			printDebug("createArtifactPackage", new String[] { "exception" }, new String[] { e.getMessage() });
			GenericUtils.printConsoleLog(listener, "Create Artifact package request could not be sent due to the exception: "+e.getMessage());
		}

		return result;
	}

	public JSONObject addBuildDetails(JSONObject artifactJSON, String jobName, String buildNumber, String stageName, String branchName) {

		if(null == artifactJSON.get(DevOpsConstants.ARTIFACT_PIPELINE_NAME.toString()))
			artifactJSON.put(DevOpsConstants.ARTIFACT_PIPELINE_NAME.toString(), jobName); // pipelineName

		if(null == artifactJSON.get(DevOpsConstants.ARTIFACT_TASK_EXEC_NUM.toString()))
			artifactJSON.put(DevOpsConstants.ARTIFACT_TASK_EXEC_NUM.toString(), buildNumber); // buildNumber

		if(null == artifactJSON.get(DevOpsConstants.ARTIFACT_STAGE_NAME.toString()) && null!=stageName)
			artifactJSON.put(DevOpsConstants.ARTIFACT_STAGE_NAME.toString(), stageName); // stageName

		if(null == artifactJSON.get(DevOpsConstants.ARTIFACT_BRANCH_NAME.toString()))
			artifactJSON.put(DevOpsConstants.ARTIFACT_BRANCH_NAME.toString(), branchName); // branchName

		return artifactJSON;
	}

}