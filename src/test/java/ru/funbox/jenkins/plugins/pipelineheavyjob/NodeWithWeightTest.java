package ru.funbox.jenkins.plugins.pipelineheavyjob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Collections;
import java.util.concurrent.ExecutionException;

import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.DumbSlave;
import hudson.slaves.RetentionStrategy;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import com.gargoylesoftware.htmlunit.Page;

import hudson.model.Result;
import hudson.model.queue.QueueTaskFuture;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;

public class NodeWithWeightTest {

    @Rule public RestartableJenkinsRule story = new RestartableJenkinsRule();

    @Test public void checkBuildQueued() throws Exception {
        // This is implicitly testing ExecutorStepExecution$PlaceholderTask as exported bean
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                // use non-existent node label to keep the build queued
                p.setDefinition(new CpsFlowDefinition("nodeWithWeight(label: 'nonexistent', weight: 2) { echo 'test' }", true));
                WorkflowRun b = scheduleAndWaitQueued(p);
                assertQueueAPIStatusOKAndAbort(b);
            }
        });
    }

    @Test public void checkHeavyBuildWaiting() throws Exception {
        // This is implicitly testing ExecutorStepExecution$PlaceholderTask as exported bean
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                story.j.createOnlineSlave(Label.get("label1"));

                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                // use weight = 10 to keep the build queued
                p.setDefinition(new CpsFlowDefinition("nodeWithWeight(label: 'label1', weight: 10) { echo 'test' }", true));
                WorkflowRun b = scheduleAndWaitQueued(p);
                assertQueueAPIStatusOKAndAbort(b);
            }
        });
    }

    @Test public void checkLightBuildComplete() throws Exception {
        // This is implicitly testing ExecutorStepExecution$PlaceholderTask as exported bean
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                story.j.createOnlineSlave(Label.get("label1"));

                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                // use weight = 10 to keep the build queued
                p.setDefinition(new CpsFlowDefinition("nodeWithWeight(label: 'label1', weight: 1) { echo 'test' }", true));
                QueueTaskFuture<WorkflowRun> build = p.scheduleBuild2(0);
                WorkflowRun b = build.getStartCondition().get();
                story.j.waitForCompletion(b);
                story.j.assertLogContains("Finished: SUCCESS", b);
            }
        });
    }

    @Test public void checkBuildWaitingInQueueComplete() throws Exception {
        // This is implicitly testing ExecutorStepExecution$PlaceholderTask as exported bean
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                DumbSlave slave;
                synchronized (story.j.jenkins) {
                    slave = new DumbSlave("slave1", "dummy",
                            story.j.createTmpDir().getPath(), "2", Node.Mode.NORMAL, "label1", story.j.createComputerLauncher(null), RetentionStrategy.NOOP, Collections.EMPTY_LIST);
                    story.j.jenkins.addNode(slave);
                }
                story.j.waitOnline(slave);

                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                // use weight = 10 to keep the build queued
                p.setDefinition(new CpsFlowDefinition("nodeWithWeight(label: 'label1', weight: 2) { sleep(5) }", true));
                Thread.sleep(1000);
                QueueTaskFuture<WorkflowRun> build = p.scheduleBuild2(0);
                WorkflowRun b1 = build.getStartCondition().get();
                WorkflowRun b2 = scheduleAndWaitQueued(p);
                assertQueueAPIStatusOKAndAbort(b2);
                story.j.waitForCompletion(b1);
                story.j.assertLogContains("Finished: SUCCESS", b1);
            }
        });
    }

    @Test public void checkBuildQueuedRestartable() throws Exception {
        // This is implicitly testing AfterRestartTask as exported bean
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.createProject(WorkflowJob.class, "p");
                // use non-existent node label to keep the build queued
                p.setDefinition(new CpsFlowDefinition("nodeWithWeight(label: 'nonexistent', weight: 2) { echo 'test' }", true));
                scheduleAndWaitQueued(p);
                // Ok, the item is in he queue now, restart
            }
        });
        story.addStep(new Statement() {
            @Override public void evaluate() throws Throwable {
                WorkflowJob p = story.j.jenkins.getItemByFullName("p", WorkflowJob.class);
                WorkflowRun b = p.getBuildByNumber(1);

                assertQueueAPIStatusOKAndAbort(b);
            }
        });
    }

    private WorkflowRun scheduleAndWaitQueued(WorkflowJob p) throws InterruptedException, ExecutionException {
        QueueTaskFuture<WorkflowRun> build = p.scheduleBuild2(0);

        WorkflowRun b = build.getStartCondition().get();
        int secondsWaiting = 0;
        while (true) {
            if (secondsWaiting > 15) {
                fail("No item queued after 15 seconds");
            }
            if (story.j.jenkins.getQueue().getItems().length > 0) {
                break;
            }
            Thread.sleep(1000);
            secondsWaiting++;
        }
        return b;
    }

    private void assertQueueAPIStatusOKAndAbort(WorkflowRun b)
            throws Exception {
        JenkinsRule.WebClient wc = story.j.createWebClient();
        Page queue = wc.goTo("queue/api/json", "application/json");

        JSONObject o = JSONObject.fromObject(queue.getWebResponse().getContentAsString());
        JSONArray items = o.getJSONArray("items");
        // Just check that the request returns HTTP 200 and there is some content.
        // Not going into de the content in this test
        assertEquals(1, items.size());

        b.getExecutor().interrupt();
        story.j.assertBuildStatus(Result.ABORTED, story.j.waitForCompletion(b));

        queue = wc.goTo("queue/api/json", "application/json");
        o = JSONObject.fromObject(queue.getWebResponse().getContentAsString());
        items = o.getJSONArray("items");

        assertEquals(0, items.size());
    }

}