package io.jenkins.plugins.freestyle.steps;

import java.io.IOException;
import java.io.Serializable;
import java.util.logging.Level;

import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import io.jenkins.plugins.config.DevOpsJobProperty;
import io.jenkins.plugins.model.DevOpsModel;
import io.jenkins.plugins.pipeline.steps.executions.DevOpsPipelineMapStepExecution;
import io.jenkins.plugins.utils.DevOpsConstants;
import io.jenkins.plugins.utils.GenericUtils;
import jenkins.tasks.SimpleBuildStep;

/**
 * Artifact package create build step.
 * Can be configured on free-style job.
 */
public class DevOpsCreateArtifactPackageBuildStep extends Builder implements SimpleBuildStep, Serializable {

	private static final long serialVersionUID = 1L;
	private String name;
	private String artifactsPayload;

	public DevOpsCreateArtifactPackageBuildStep() {
		super();
	}

	@DataBoundConstructor
	public DevOpsCreateArtifactPackageBuildStep(String name, String artifactsPayload) {
		this.name = name;
		this.artifactsPayload = artifactsPayload;
	}

	public String getArtifactsPayload() {
		return artifactsPayload;
	}

	public String getName() {
		return name;
	}

	@DataBoundSetter
	public void setArtifactsPayload(String artifactsPayload) {
		this.artifactsPayload = artifactsPayload;
	}

	@DataBoundSetter
	public void setName(String name) {
		this.name = name;
	}

	public void perform(StepContext stepContext, Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener, EnvVars envVars)
			throws InterruptedException, IOException {

		if (envVars == null)
			envVars = GenericUtils.getEnvVars(run, listener);

		DevOpsModel model = new DevOpsModel();

		DevOpsJobProperty jobProperties = model.getJobProperty(run.getParent());

		// Resolve payload (to replace env/system variables)
		String expandedPayload = envVars.expand(this.artifactsPayload);

		// Resolve package name
		String expandedName = envVars.expand(this.name);

		String _result = model.handleArtifactCreatePackage(stepContext, run, listener, expandedName, expandedPayload, envVars);

		printDebug("perform", new String[]{"message"},
				new String[]{"handleArtifactCreatePackage responded with: " + _result}, Level.INFO);

		if (null != _result && !_result.contains(DevOpsConstants.COMMON_RESULT_FAILURE.toString())) {
			GenericUtils.printConsoleLog(listener, "SUCCESS: Register package request was successful.");
			printDebug("perform", new String[]{"message"},
					new String[]{"SUCCESS : Register package request was successful."},
					Level.INFO);
		} else {
			String errorMsg = "FAILED: Artifact package could not be created.";
			if (null != _result)
				errorMsg += " Cause: " + _result;
			printDebug("perform", new String[]{"message"},
					new String[]{errorMsg}, Level.WARNING);
			GenericUtils.printConsoleLog(listener, errorMsg);
			if (jobProperties != null && jobProperties.isIgnoreSNErrors()) {
				GenericUtils.printConsoleLog(listener, "IGNORED: Artifact package creation error ignored.");
			} else { // TODO: Should we really abort here?
				throw new AbortException(errorMsg);
			}

		}
	}


	@Override
	public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener)
			throws InterruptedException, IOException {

		perform(null, run, workspace, launcher, listener, null);
	}

	@Override
	public DescriptorImpl getDescriptor() {
		return (DescriptorImpl) super.getDescriptor();
	}


	@Extension
	public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

		public DescriptorImpl() {
			load();
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		@Override
		public String getDisplayName() {
			return DevOpsConstants.ARTIFACT_PACKAGE_STEP_DISPLAY_NAME.toString();
		}
	}

	private void printDebug(String methodName, String[] variables, String[] values, Level logLevel) {
		GenericUtils.printDebug(DevOpsPipelineMapStepExecution.class.getName(), methodName, variables, values, logLevel);
	}

}
