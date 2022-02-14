package ru.funbox.jenkins.plugins.pipelineheavyjob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.concurrent.ExecutionException;

import hudson.model.Label;
import hudson.slaves.DumbSlave;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.jvnet.hudson.test.JenkinsRule;

import com.gargoylesoftware.htmlunit.Page;

import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

public class NodeWithWeightTest {

    @Rule public JenkinsRule j = new JenkinsRule();
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    @Test public void checkBuildQueued() throws Exception {
        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        // use non-existent node label to keep the build queued
        p.setDefinition(new CpsFlowDefinition("nodeWithWeight(label: 'nonexistent', weight: 2) { echo 'test' }", true));
        WorkflowRun b = scheduleAndWaitQueued(p);
        assertQueueAPIStatusOKAndAbort(b);
    }

    @Test public void checkHeavyBuildWaiting() throws Exception {
        j.createOnlineSlave(Label.get("label1"));

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        // use weight = 10 to keep the build queued
        p.setDefinition(new CpsFlowDefinition("nodeWithWeight(label: 'label1', weight: 10) { echo 'test' }", true));
        WorkflowRun b = scheduleAndWaitQueued(p);
        assertQueueAPIStatusOKAndAbort(b);
    }

    @Test public void checkLightBuildComplete() throws Exception {
        j.createOnlineSlave(Label.get("label1"));

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("nodeWithWeight(label: 'label1', weight: 1) { echo 'test' }", true));
        WorkflowRun b = p.scheduleBuild2(0).waitForStart();
        j.waitForMessage("Finished: SUCCESS", b);
    }

    @Test public void checkBuildWaitingInQueue() throws Exception {
        DumbSlave slave = new DumbSlave("slave1", folder.newFolder("slave").getPath(), j.createComputerLauncher(null));
        slave.setLabelString("label1");
        slave.setNumExecutors(3);
        j.jenkins.addNode(slave);
        j.waitOnline(slave);

        WorkflowJob p = j.jenkins.createProject(WorkflowJob.class, "p");
        p.setDefinition(new CpsFlowDefinition("nodeWithWeight(label: 'label1', weight: 2) { input('Hello') }", true));
        WorkflowRun b1 = p.scheduleBuild2(0).waitForStart();
        WorkflowRun b2 = scheduleAndWaitQueued(p);
        assertQueueAPIStatusOKAndAbort(b2);
        b1.getExecutor().interrupt();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b1));
    }

    private WorkflowRun scheduleAndWaitQueued(WorkflowJob p) throws InterruptedException, ExecutionException {
        QueueTaskFuture<WorkflowRun> build = p.scheduleBuild2(0);

        WorkflowRun b = build.getStartCondition().get();
        int secondsWaiting = 0;
        while (true) {
            if (secondsWaiting > 15) {
                fail("No item queued after 15 seconds");
            }
            if (j.jenkins.getQueue().getItems().length > 0) {
                break;
            }
            Thread.sleep(1000);
            secondsWaiting++;
        }
        return b;
    }

    private void assertQueueAPIStatusOKAndAbort(WorkflowRun b)
            throws Exception {
        JenkinsRule.WebClient wc = j.createWebClient();
        Page queue = wc.goTo("queue/api/json", "application/json");

        JSONObject o = JSONObject.fromObject(queue.getWebResponse().getContentAsString());
        JSONArray items = o.getJSONArray("items");
        // Just check that the request returns HTTP 200 and there is some content.
        // Not going into de the content in this test
        assertEquals(1, items.size());

        b.getExecutor().interrupt();
        j.assertBuildStatus(Result.ABORTED, j.waitForCompletion(b));

        queue = wc.goTo("queue/api/json", "application/json");
        o = JSONObject.fromObject(queue.getWebResponse().getContentAsString());
        items = o.getJSONArray("items");

        assertEquals(0, items.size());
    }

}