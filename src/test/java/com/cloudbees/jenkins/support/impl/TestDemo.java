package com.cloudbees.jenkins.support.impl;

import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.security.GlobalMatrixAuthorizationStrategy;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.recipes.LocalData;
import org.jvnet.hudson.test.TestExtension;
import hudson.security.HudsonPrivateSecurityRealm;
import static org.junit.Assert.*;

public class TestDemo {

    @Rule
    public JenkinsRule jenkinsRule = new JenkinsRule();

    @Test
    public void testPipelineExecution() throws Exception {
        // Create a Pipeline job
        WorkflowJob job = jenkinsRule.createProject(WorkflowJob.class, "test-pipeline");


        // Define the pipeline script
        String pipelineScript =
                "pipeline {\n" +
                        "    agent any\n" +
                        "    stages {\n" +
                        "        stage('Test') {\n" +
                        "            steps {\n" +
                        "                echo 'Running pipeline test...'\n" +
                        "            }\n" +
                        "        }\n" +
                        "    }\n" +
                        "}";

        // Set the pipeline definition
        job.setDefinition(new CpsFlowDefinition(pipelineScript, true));

        // Schedule a build and wait for completion
        WorkflowRun run = job.scheduleBuild2(0).waitForStart();
        jenkinsRule.waitUntilNoActivity();

        // Verify the build ran successfully
        assertNotNull(run);
        assertTrue(run.getResult().isBetterOrEqualTo(hudson.model.Result.SUCCESS));

        jenkinsRule.interactiveBreak();

    }
}
