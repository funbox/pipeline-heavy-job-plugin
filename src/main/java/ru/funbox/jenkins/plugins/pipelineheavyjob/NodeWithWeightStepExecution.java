package ru.funbox.jenkins.plugins.pipelineheavyjob;

import hudson.AbortException;
import hudson.Extension;
import hudson.model.*;
import hudson.model.Queue;
import hudson.model.queue.QueueListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import java.io.PrintStream;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static java.util.logging.Level.*;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import jenkins.model.Jenkins;
import jenkins.util.Timer;
import org.jenkinsci.plugins.workflow.actions.QueueItemAction;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.steps.AbstractStepExecutionImpl;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.durable_task.Messages;

public class NodeWithWeightStepExecution extends AbstractStepExecutionImpl {

    private final NodeWithWeightStep step;

    NodeWithWeightStepExecution(StepContext context, NodeWithWeightStep step) {
        super(context);
        this.step = step;
    }

    /**
     * General strategy of this step.
     *
     * 1. schedule {@link PlaceholderTask} into the {@link Queue} (what this method does)
     * 2. when {@link PlaceholderTask} starts running, invoke the closure
     * 3. when the closure is done, let {@link PlaceholderTask} complete
     */
    @Override
    public boolean start() throws Exception {
        final PlaceholderTask task = new PlaceholderTask(getContext(), step.getLabel(), step.getWeight());
        Queue.WaitingItem waitingItem = Queue.getInstance().schedule2(task, 0).getCreateItem();
        if (waitingItem == null) {
            // There can be no duplicates. But could be refused if a QueueDecisionHandler rejects it for some odd reason.
            throw new IllegalStateException("failed to schedule task");
        }
        getContext().get(FlowNode.class).addAction(new QueueItemActionImpl(waitingItem.getId()));

        Timer.get().schedule(new Runnable() {
            @Override public void run() {
                Queue.Item item = Queue.getInstance().getItem(task);
                if (item != null) {
                    PrintStream logger;
                    try {
                        logger = getContext().get(TaskListener.class).getLogger();
                    } catch (Exception x) { // IOException, InterruptedException
                        LOGGER.log(FINE, "could not print message to build about " + item + "; perhaps it is already completed", x);
                        return;
                    }
                    logger.println("Still waiting to schedule task");
                    String why = item.getWhy();
                    if (why != null) {
                        logger.println(why);
                    }
                }
            }
        }, 15, TimeUnit.SECONDS);
        return false;
    }

    @Override
    public void stop(@Nonnull Throwable cause) throws Exception {
        Queue.Item[] items;
        try (ACLContext as = ACL.as(ACL.SYSTEM)) {
            items = Queue.getInstance().getItems();
        }
        LOGGER.log(FINE, "stopping one of {0}", Arrays.asList(items));
        StepContext context = getContext();
        for (Queue.Item item : items) {
            // if we are still in the queue waiting to be scheduled, just retract that
            if (item.task instanceof PlaceholderTask) {
                PlaceholderTask task = (PlaceholderTask) item.task;
                if (task.getContext().equals(context)) {
                    task.stopping = true;
                    Queue.getInstance().cancel(item);
                    LOGGER.log(FINE, "canceling {0}", item);
                    break;
                } else {
                    LOGGER.log(FINE, "no match on {0} with {1} vs. {2}", new Object[] {item, task.getContext(), context});
                }
            } else {
                LOGGER.log(FINE, "no match on {0}", item);
            }
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null) {
            // if we are already running, kill the ongoing activities, which releases PlaceholderExecutable from its sleep loop
            // Similar to Executor.of, but distinct since we do not have the Executable yet:
            COMPUTERS: for (Computer c : j.getComputers()) {
                for (Executor e : c.getExecutors()) {
                    Queue.Executable exec = e.getCurrentExecutable();
                    if (exec instanceof PlaceholderTask.PlaceholderExecutable) {
                        StepContext actualContext = ((PlaceholderTask.PlaceholderExecutable) exec).getParent().getContext();
                        if (actualContext.equals(context)) {
                            PlaceholderTask.finish(((PlaceholderTask.PlaceholderExecutable) exec).getParent().getCookie());
                            LOGGER.log(FINE, "canceling {0}", exec);
                            break COMPUTERS;
                        } else {
                            LOGGER.log(FINE, "no match on {0} with {1} vs. {2}", new Object[] {exec, actualContext, context});
                        }
                    } else {
                        LOGGER.log(FINE, "no match on {0}", exec);
                    }
                }
            }
        }
        // Whether or not either of the above worked (and they would not if for example our item were canceled), make sure we die.
        super.stop(cause);
    }

    @Override public void onResume() {
        super.onResume();
        // See if we are still running, or scheduled to run. Cf. stop logic above.
        try {
            Run<?, ?> run = getContext().get(Run.class);
            for (Queue.Item item : Queue.getInstance().getItems()) {
                if (item.task instanceof PlaceholderTask && ((PlaceholderTask) item.task).getContext().equals(getContext())) {
                    LOGGER.log(FINE, "Queue item for node block in {0} is still waiting after reload", run);
                    return;
                }
            }
            Jenkins j = Jenkins.getInstanceOrNull();
            if (j != null) {
                for (Computer c : j.getComputers()) {
                    for (Executor e : c.getExecutors()) {
                        Queue.Executable exec = e.getCurrentExecutable();
                        if (exec instanceof PlaceholderTask.PlaceholderExecutable && ((PlaceholderTask.PlaceholderExecutable) exec).getParent().getContext().equals(getContext())) {
                            LOGGER.log(FINE, "Node block in {0} is running on {1} after reload", new Object[] {run, c.getName()});
                            return;
                        }
                    }
                }
            }
            TaskListener listener = getContext().get(TaskListener.class);
            if (step == null) { // compatibility: used to be transient
                listener.getLogger().println("Queue item for node block in " + run.getFullDisplayName() + " is missing (perhaps JENKINS-34281), but cannot reschedule");
                return;
            }
            listener.getLogger().println("Queue item for node block in " + run.getFullDisplayName() + " is missing (perhaps JENKINS-34281); rescheduling");
            start();
        } catch (Exception x) { // JENKINS-40161
            getContext().onFailure(x);
        }
    }

    @Override public String getStatus() {
        // Yet another copy of the same logic; perhaps this should be factored into some method returning a union of Queue.Item and PlaceholderExecutable?
        for (Queue.Item item : Queue.getInstance().getItems()) {
            if (item.task instanceof PlaceholderTask && ((PlaceholderTask) item.task).getContext().equals(getContext())) {
                return "waiting for " + item.task.getFullDisplayName() + " to be scheduled; blocked: " + item.getWhy();
            }
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null) {
            for (Computer c : j.getComputers()) {
                for (Executor e : c.getExecutors()) {
                    Queue.Executable exec = e.getCurrentExecutable();
                    if (exec instanceof PlaceholderTask.PlaceholderExecutable && ((PlaceholderTask.PlaceholderExecutable) exec).getParent().getContext().equals(getContext())) {
                        return "running on " + c.getName();
                    }
                }
            }
        }
        return "node block appears to be neither running nor scheduled";
    }

    @Extension public static class CancelledItemListener extends QueueListener {

        @Override public void onLeft(Queue.LeftItem li) {
            if (li.isCancelled()) {
                if (li.task instanceof PlaceholderTask) {
                    PlaceholderTask task = (PlaceholderTask) li.task;
                    if (!task.stopping) {
                        task.getContext().onFailure(new AbortException(Messages.ExecutorStepExecution_queue_task_cancelled()));
                    }
                }
            }
        }

    }

    private static final class QueueItemActionImpl extends QueueItemAction {
        /**
         * Used to identify the task in the queue, so that its status can be identified.
         */
        private final long id;

        QueueItemActionImpl(long id) {
            this.id = id;
        }

        @Override
        @CheckForNull
        public Queue.Item itemInQueue() {
            return Queue.getInstance().getItem(id);
        }
    }

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(NodeWithWeightStepExecution.class.getName());
}
