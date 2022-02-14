package ru.funbox.jenkins.plugins.pipelineheavyjob;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.*;
import hudson.console.ModelHyperlinkNote;
import hudson.model.*;
import hudson.model.Queue;
import hudson.model.queue.CauseOfBlockage;
import hudson.model.queue.SubTask;
import hudson.remoting.ChannelClosedException;
import hudson.remoting.RequestAbortedException;
import hudson.security.ACL;
import hudson.security.AccessControlled;
import hudson.security.Permission;
import hudson.slaves.OfflineCause;
import hudson.slaves.WorkspaceList;
import jenkins.model.Jenkins;
import jenkins.model.queue.AsynchronousExecution;
import jenkins.security.QueueItemAuthenticator;
import jenkins.security.QueueItemAuthenticatorProvider;
import jenkins.util.Timer;
import org.acegisecurity.AccessDeniedException;
import org.acegisecurity.Authentication;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.jenkinsci.plugins.durabletask.executors.ContinuableExecutable;
import org.jenkinsci.plugins.durabletask.executors.ContinuedTask;
import org.jenkinsci.plugins.workflow.actions.LabelAction;
import org.jenkinsci.plugins.workflow.actions.ThreadNameAction;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.graphanalysis.FlowScanningUtils;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.support.actions.WorkspaceActionImpl;
import org.jenkinsci.plugins.workflow.support.concurrent.Timeout;
import org.jenkinsci.plugins.workflow.support.steps.FilePathDynamicContext;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;

@ExportedBean
public final class PlaceholderTask implements ContinuedTask, Serializable, AccessControlled {
    /** Transient handle of a running executor task. */
    private static final class RunningTask {
        /** null until placeholder executable runs */
        @Nullable
        AsynchronousExecution execution;
        /** null until placeholder executable runs */
        @Nullable Launcher launcher;
    }

    private static final String COOKIE_VAR = "JENKINS_NODE_COOKIE";

    /** keys are {@link #cookie}s */
    private static final Map<String, RunningTask> runningTasks = new HashMap<String, RunningTask>();

    private final StepContext context;
    /** Initially set to {@link NodeWithWeightStep#getLabel}, if any; later switched to actual self-label when block runs. */
    private String label;
    private final int weight;
    /** Shortcut for {@link #run}. */
    private final String runId;
    /**
     * Unique cookie set once the task starts.
     * Serves multiple purposes:
     * identifies whether we have already invoked the body (since this can be rerun after restart);
     * serves as a key for {@link #runningTasks} and {@link Callback} (cannot just have a doneness flag in {@link PlaceholderTask} because multiple copies might be deserialized);
     * and allows {@link Launcher#kill} to work.
     */
    private String cookie;

    /** {@link Authentication#getName} of user of build, if known. */
    private final @CheckForNull
    String auth;

    /** Flag to remember that {@link NodeWithWeightStepExecution#stop} is being called, so {@link NodeWithWeightStepExecution.CancelledItemListener} can be suppressed. */
    public transient boolean stopping;

    PlaceholderTask(StepContext context, String label, int weight) throws IOException, InterruptedException {
        this.context = context;
        this.label = label;
        this.weight = weight;
        runId = context.get(Run.class).getExternalizableId();
        Authentication runningAuth = Jenkins.getAuthentication();
        if (runningAuth.equals(ACL.SYSTEM)) {
            auth = null;
        } else {
            auth = runningAuth.getName();
        }
        LOGGER.log(FINE, "scheduling {0}", this);
    }

    public StepContext getContext() {
        return context;
    }

    private Object readResolve() {
        if (cookie != null) {
            synchronized (runningTasks) {
                runningTasks.put(cookie, new RunningTask());
            }
        }
        LOGGER.log(FINE, "deserializing previously scheduled {0}", this);
        return this;
    }

    /**
     * Gives {@link FlowNode}, waiting to be executed  in build {@link Queue}.
     *
     * @return FlowNode instance, could be null.
     */
    public @CheckForNull FlowNode getNode() throws IOException, InterruptedException {
        return context.get(FlowNode.class);
    }

    @Override public Queue.Executable createExecutable() throws IOException {
        return new PlaceholderExecutable();
    }

    @CheckForNull
    public String getCookie() {
        return cookie;
    }

    @Override public Label getAssignedLabel() {
        if (label == null) {
            return null;
        } else if (label.isEmpty()) {
            Jenkins j = Jenkins.getInstanceOrNull();
            if (j == null) {
                return null;
            }
            return j.getSelfLabel();
        } else {
            return Label.get(label);
        }
    }

    @Override public Node getLastBuiltOn() {
        if (label == null) {
            return null;
        }
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) {
            return null;
        }
        return j.getNode(label);
    }

    @Override public CauseOfBlockage getCauseOfBlockage() {
        return null;
    }

    @Override public boolean isConcurrentBuild() {
        return false;
    }

    @Override public Collection<? extends SubTask> getSubTasks() {
        List<SubTask> r = new ArrayList<SubTask>();
        r.add(this);
        for (int i=1; i < weight; i++)
            r.add(new WasteTimeSubTask());
        return r;
    }

    @ExportedBean
    public final class WasteTimeSubTask implements SubTask {
        public Queue.Executable createExecutable() {
            return new ExecutableImpl(this);
        }

        @Override
        public Object getSameNodeConstraint() {
            // must occupy the same node as the project itself
            return PlaceholderTask.this; // see javadoc SubTask getSameNodeConstraint
        }

        @Override
        public long getEstimatedDuration() {
            return PlaceholderTask.this.getEstimatedDuration();
        }

        @Override
        public Queue.Task getOwnerTask() {
            return PlaceholderTask.this;
        }

        @Exported
        @Override
        public String getDisplayName() {
            return "part of " + PlaceholderTask.this.getDisplayName();
        }

        @Exported
        public String getFullDisplayName() {
            return "part of " + PlaceholderTask.this.getDisplayName();
        }
    }

    @Nonnull
    @Override public Queue.Task getOwnerTask() {
        Run<?,?> r = runForDisplay();
        if (r != null && r.getParent() instanceof Queue.Task) {
            return (Queue.Task) r.getParent();
        } else {
            return this;
        }
    }

    @Override public Object getSameNodeConstraint() {
        return this; // see javadoc SubTask getSameNodeConstraint
    }

    /**
     * Something we can use to check abort and read permissions.
     * Normally this will be a {@link Run}.
     * However if things are badly broken, for example if the build has been deleted,
     * then as a fallback we use the Jenkins root.
     * This allows an administrator to clean up dead queue items and executor cells.
     * TODO make {@link FlowExecutionOwner} implement {@link AccessControlled}
     * so that an implementation could fall back to checking {@link Job} permission.
     */
    @Override public ACL getACL() {
        try {
            if (!context.isReady()) {
                return Jenkins.get().getACL();
            }
            FlowExecution exec = context.get(FlowExecution.class);
            if (exec == null) {
                return Jenkins.get().getACL();
            }
            Queue.Executable executable = exec.getOwner().getExecutable();
            if (executable instanceof AccessControlled) {
                return ((AccessControlled) executable).getACL();
            } else {
                return Jenkins.get().getACL();
            }
        } catch (Exception x) {
            LOGGER.log(FINE, null, x);
            return Jenkins.get().getACL();
        }
    }

    @Override public void checkAbortPermission() {
        checkPermission(Item.CANCEL);
    }

    @Override public boolean hasAbortPermission() {
        return hasPermission(Item.CANCEL);
    }

    public @CheckForNull Run<?,?> run() {
        try {
            if (!context.isReady()) {
                return null;
            }
            return context.get(Run.class);
        } catch (Exception x) {
            LOGGER.log(FINE, "broken " + cookie, x);
            finish(cookie); // probably broken, so just shut it down
            return null;
        }
    }

    public @CheckForNull Run<?,?> runForDisplay() {
        Run<?,?> r = run();
        if (r == null && /* not stored prior to 1.13 */runId != null) {
            SecurityContext orig = ACL.impersonate(ACL.SYSTEM);
            try {
                return Run.fromExternalizableId(runId);
            } finally {
                SecurityContextHolder.setContext(orig);
            }
        }
        return r;
    }

    @Override public String getUrl() {
        // TODO ideally this would be found via FlowExecution.owner.executable, but how do we check for something with a URL? There is no marker interface for it: JENKINS-26091
        Run<?,?> r = runForDisplay();
        return r != null ? r.getUrl() : "";
    }

    @Override public String getDisplayName() {
        // TODO more generic to check whether FlowExecution.owner.executable is a ModelObject
        Run<?,?> r = runForDisplay();
        if (r != null) {
            String runDisplayName = r.getFullDisplayName();
            String enclosingLabel = getEnclosingLabel();
            if (enclosingLabel != null) {
                return String.format("%s (%s)", runDisplayName, enclosingLabel);
            } else {
                return String.format("part of %s", runDisplayName);
            }
        } else {
            return String.format("part of %s", runId);
        }
    }

    @Override public String getName() {
        return getDisplayName();
    }

    @Override public String getFullDisplayName() {
        return getDisplayName();
    }

    static String findLabelName(FlowNode flowNode){
        LabelAction la = flowNode.getPersistentAction(LabelAction.class);

        if (la != null) {
            return la.getDisplayName();
        }
        return null;
    }

    /**
     * Similar to {@link #getEnclosingLabel()}.
     * However instead of returning the innermost label including labels inside node blocks this one
     * concatenates all labels found outside the current (node) block
     *
     * As {@link FlowNode#getEnclosingBlocks()} will return the blocks sorted from inner to outer blocks
     * this method will create a string like
     * <code>#innerblock#outerblock for</code> for a script like
     * <pre>
     *     {@code
     *     parallel(outerblock: {
     *         stage('innerblock') {
     *             node {
     *                 // .. do something here
     *             }
     *         }
     *     }
     *     }
     * </pre>
     *
     * In case there's no context available or we get a timeout we'll just return <code>baseLabel</code>
     *
     * */
    private String concatenateAllEnclosingLabels(StringBuilder labelName) {
        if (!context.isReady()) {
            return labelName.toString();
        }
        FlowNode executorStepNode = null;
        try (Timeout t = Timeout.limit(100, TimeUnit.MILLISECONDS)) {
            executorStepNode = context.get(FlowNode.class);
        } catch (Exception x) {
            LOGGER.log(Level.FINE, null, x);
        }

        if (executorStepNode != null) {
            for(FlowNode node: executorStepNode.getEnclosingBlocks()) {
                String currentLabelName = findLabelName(node);
                if (currentLabelName != null) {
                    labelName.append("#");
                    labelName.append(currentLabelName);
                }
            }
        }

        return labelName.toString();
    }

    /**
     * Provide unique key which will be used to prioritize the list of possible build agents to use
     * */
    @Override
    public String getAffinityKey() {
        StringBuilder ownerTaskName = new StringBuilder(getOwnerTask().getName());
        return concatenateAllEnclosingLabels(ownerTaskName);
    }

    /** hash code of list of heads */
    private transient int lastCheckedHashCode;
    private transient String lastEnclosingLabel;
    @Restricted(NoExternalUse.class) // for Jelly
    public @CheckForNull String getEnclosingLabel() {
        if (!context.isReady()) {
            return null;
        }
        FlowNode executorStepNode;
        try (Timeout t = Timeout.limit(100, TimeUnit.MILLISECONDS)) {
            executorStepNode = context.get(FlowNode.class);
        } catch (Exception x) {
            LOGGER.log(Level.FINE, null, x);
            return null;
        }
        if (executorStepNode == null) {
            return null;
        }
        List<FlowNode> heads = executorStepNode.getExecution().getCurrentHeads();
        int headsHash = heads.hashCode(); // deterministic based on IDs of those heads
        if (headsHash == lastCheckedHashCode) {
            return lastEnclosingLabel;
        } else {
            lastCheckedHashCode = headsHash;
            return lastEnclosingLabel = computeEnclosingLabel(executorStepNode, heads);
        }
    }
    private String computeEnclosingLabel(FlowNode executorStepNode, List<FlowNode> heads) {
        for (FlowNode runningNode : heads) {
            // See if this step is inside our node {} block, and track the associated label.
            boolean match = false;
            String enclosingLabel = null;
            Iterator<FlowNode> it = FlowScanningUtils.fetchEnclosingBlocks(runningNode);
            int count = 0;
            while (it.hasNext()) {
                FlowNode n = it.next();
                if (enclosingLabel == null) {
                    ThreadNameAction tna = n.getPersistentAction(ThreadNameAction.class);
                    if (tna != null) {
                        enclosingLabel = tna.getThreadName();
                    } else {
                        LabelAction a = n.getPersistentAction(LabelAction.class);
                        if (a != null) {
                            enclosingLabel = a.getDisplayName();
                        }
                    }
                    if (match && enclosingLabel != null) {
                        return enclosingLabel;
                    }
                }
                if (n.equals(executorStepNode)) {
                    if (enclosingLabel != null) {
                        return enclosingLabel;
                    }
                    match = true;
                }
                if (count++ > 100) {
                    break; // not important enough to bother
                }
            }
        }
        return null;
    }

    @Override public long getEstimatedDuration() {
        Run<?,?> r = run();
        // Not accurate if there are multiple slaves in one build, but better than nothing:
        return r != null ? r.getEstimatedDuration() : -1;
    }

    @Override public ResourceList getResourceList() {
        return new ResourceList();
    }

    @Nonnull
    @Override public Authentication getDefaultAuthentication() {
        return ACL.SYSTEM;
    }

    @Override public Authentication getDefaultAuthentication(Queue.Item item) {
        return getDefaultAuthentication();
    }

    @Restricted(NoExternalUse.class)
    @Extension(ordinal=959) public static class AuthenticationFromBuild extends QueueItemAuthenticatorProvider {
        @Nonnull
        @Override public List<QueueItemAuthenticator> getAuthenticators() {
            return Collections.singletonList(new QueueItemAuthenticator() {
                @Override public Authentication authenticate(Queue.Task task) {
                    if (task instanceof PlaceholderTask) {
                        String auth = ((PlaceholderTask) task).auth;
                        LOGGER.log(FINE, "authenticating {0}", task);
                        if (Jenkins.ANONYMOUS.getName().equals(auth)) {
                            return Jenkins.ANONYMOUS;
                        } else if (auth != null) {
                            User user = User.getById(auth, false);
                            return user != null ? user.impersonate() : Jenkins.ANONYMOUS;
                        }
                    }
                    return null;
                }
            });
        }
    }

    @Override public boolean isContinued() {
        return cookie != null; // in which case this is after a restart and we still claim the executor
    }

    @Override public String toString() {
        return "ExecutorStepExecution.PlaceholderTask{runId=" + runId + ",label=" + label + ",context=" + context + ",cookie=" + cookie + ",auth=" + auth + '}';
    }

    public static void finish(@CheckForNull final String cookie) {
        if (cookie == null) {
            return;
        }
        synchronized (runningTasks) {
            final RunningTask runningTask = runningTasks.remove(cookie);
            if (runningTask == null) {
                LOGGER.log(FINE, "no running task corresponds to {0}", cookie);
                return;
            }
            final AsynchronousExecution execution = runningTask.execution;
            if (execution == null) {
                // JENKINS-30759: finished before asynch execution was even scheduled
                return;
            }
            assert runningTask.launcher != null;
            jenkins.util.Timer.get().submit(new Runnable() { // JENKINS-31614
                @Override public void run() {
                    execution.completed(null);
                }
            });
            Computer.threadPoolForRemoting.submit(new Runnable() { // JENKINS-34542, JENKINS-45553
                @Override
                public void run() {
                    try {
                        runningTask.launcher.kill(Collections.singletonMap(COOKIE_VAR, cookie));
                    } catch (ChannelClosedException x) {
                        // fine, Jenkins was shutting down
                    } catch (RequestAbortedException x) {
                        // slave was exiting; too late to kill subprocesses
                    } catch (Exception x) {
                        LOGGER.log(Level.WARNING, "failed to shut down " + cookie, x);
                    }
                }
            });
        }
    }

    /**
     * Called when the body closure is complete.
     */
    @SuppressFBWarnings(value="SE_BAD_FIELD", justification="lease is pickled")
    private static final class Callback extends BodyExecutionCallback.TailCall {

        private final String cookie;
        private WorkspaceList.Lease lease;

        Callback(String cookie, WorkspaceList.Lease lease) {
            this.cookie = cookie;
            this.lease = lease;
        }

        @Override protected void finished(StepContext context) throws Exception {
            LOGGER.log(FINE, "finished {0}", cookie);
            lease.release();
            lease = null;
            finish(cookie);
        }

    }

    /**
     * Occupies {@link Executor} while workflow uses this slave.
     */
    @ExportedBean
    public final class PlaceholderExecutable implements ContinuableExecutable, AccessControlled {

        @Override public void run() {
            TaskListener listener = null;
            Launcher launcher;
            final Run<?, ?> r;
            Computer computer = null;
            try {
                Executor exec = Executor.currentExecutor();
                if (exec == null) {
                    throw new IllegalStateException("running task without associated executor thread");
                }
                computer = exec.getOwner();
                // Set up context for other steps inside this one.
                Node node = computer.getNode();
                if (node == null) {
                    throw new IllegalStateException("running computer lacks a node");
                }
                listener = context.get(TaskListener.class);
                launcher = node.createLauncher(listener);
                r = context.get(Run.class);
                if (cookie == null) {
                    // First time around.
                    cookie = UUID.randomUUID().toString();
                    // Switches the label to a self-label, so if the executable is killed and restarted via ExecutorPickle, it will run on the same node:
                    label = computer.getName();

                    EnvVars env = computer.getEnvironment();
                    env.overrideExpandingAll(computer.buildEnvironment(listener));
                    env.put(COOKIE_VAR, cookie);
                    // Cf. CoreEnvironmentContributor:
                    if (exec.getOwner() instanceof Jenkins.MasterComputer) {
                        env.put("NODE_NAME", "master");
                    } else {
                        env.put("NODE_NAME", label);
                    }
                    env.put("EXECUTOR_NUMBER", String.valueOf(exec.getNumber()));
                    env.put("NODE_LABELS", Util.join(node.getAssignedLabels(), " "));

                    synchronized (runningTasks) {
                        runningTasks.put(cookie, new RunningTask());
                    }
                    // For convenience, automatically allocate a workspace, like WorkspaceStep would:
                    Job<?,?> j = r.getParent();
                    if (!(j instanceof TopLevelItem)) {
                        throw new Exception(j + " must be a top-level job");
                    }
                    FilePath p = node.getWorkspaceFor((TopLevelItem) j);
                    if (p == null) {
                        throw new IllegalStateException(node + " is offline");
                    }
                    WorkspaceList.Lease lease = computer.getWorkspaceList().allocate(p);
                    FilePath workspace = lease.path;
                    // Cf. AbstractBuild.getEnvironment:
                    env.put("WORKSPACE", workspace.getRemote());
                    FlowNode flowNode = context.get(FlowNode.class);
                    if (flowNode != null) {
                        flowNode.addAction(new WorkspaceActionImpl(workspace, flowNode));
                    }
                    listener.getLogger().println("Running on " + ModelHyperlinkNote.encodeTo(node) + " in " + workspace);
                    context.newBodyInvoker()
                            .withContexts(exec, computer, env,
                                    FilePathDynamicContext.createContextualObject(workspace))
                            .withCallback(new Callback(cookie, lease))
                            .start();
                    LOGGER.log(FINE, "started {0}", cookie);
                } else {
                    // just rescheduled after a restart; wait for task to complete
                    LOGGER.log(FINE, "resuming {0}", cookie);
                }
            } catch (Exception x) {
                if (computer != null) {
                    for (Computer.TerminationRequest tr : computer.getTerminatedBy()) {
                        x.addSuppressed(tr);
                    }
                    if (listener != null) {
                        OfflineCause oc = computer.getOfflineCause();
                        if (oc != null) {
                            listener.getLogger().println(computer.getDisplayName() + " was marked offline: " + oc);
                        }
                    }
                }
                context.onFailure(x);
                return;
            }
            // wait until the invokeBodyLater call above completes and notifies our Callback object
            synchronized (runningTasks) {
                LOGGER.log(FINE, "waiting on {0}", cookie);
                RunningTask runningTask = runningTasks.get(cookie);
                if (runningTask == null) {
                    LOGGER.log(FINE, "running task apparently finished quickly for {0}", cookie);
                    return;
                }
                assert runningTask.execution == null;
                assert runningTask.launcher == null;
                runningTask.launcher = launcher;
                TaskListener _listener = listener;
                runningTask.execution = new AsynchronousExecution() {
                    @Override public void interrupt(boolean forShutdown) {
                        if (forShutdown) {
                            return;
                        }
                        LOGGER.log(FINE, "interrupted {0}", cookie);
                        // TODO save the BodyExecution somehow and call .cancel() here; currently we just interrupt the build as a whole:
                        Timer.get().submit(new Runnable() { // JENKINS-46738
                            @Override public void run() {
                                Executor masterExecutor = r.getExecutor();
                                if (masterExecutor != null) {
                                    masterExecutor.interrupt();
                                } else { // anomalous state; perhaps build already aborted but this was left behind; let user manually cancel executor slot
                                    Executor thisExecutor = /* AsynchronousExecution. */getExecutor();
                                    if (thisExecutor != null) {
                                        thisExecutor.recordCauseOfInterruption(r, _listener);
                                    }
                                    completed(null);
                                }
                            }
                        });
                    }
                    @Override public boolean blocksRestart() {
                        return false;
                    }
                    @Override public boolean displayCell() {
                        return true;
                    }
                };
                throw runningTask.execution;
            }
        }

        @Nonnull
        @Override public PlaceholderTask getParent() {
            return PlaceholderTask.this;
        }

        @Exported
        public Integer getNumber() {
            Run<?, ?> r = getParent().runForDisplay();
            return r != null ? r.getNumber() : null;
        }

        @Exported
        public String getFullDisplayName() {
            return getParent().getFullDisplayName();
        }

        @Exported
        public String getDisplayName() {
            return getParent().getDisplayName();
        }

        @Exported
        @Override public long getEstimatedDuration() {
            return getParent().getEstimatedDuration();
        }

        @Exported
        public Long getTimestamp() {
            Run<?, ?> r = getParent().runForDisplay();
            return r != null ? r.getStartTimeInMillis() : null;
        }

        @Override public boolean willContinue() {
            synchronized (runningTasks) {
                return runningTasks.containsKey(cookie);
            }
        }

        @Restricted(DoNotUse.class) // for Jelly
        public @CheckForNull Executor getExecutor() {
            return Executor.of(this);
        }

        @Restricted(NoExternalUse.class) // for Jelly and toString
        public String getUrl() {
            return PlaceholderTask.this.getUrl(); // we hope this has a console.jelly
        }

        @Exported(name="url")
        public String getAbsoluteUrl() {
            Run<?,?> r = runForDisplay();
            if (r == null) {
                return "";
            }
            Jenkins j = Jenkins.getInstanceOrNull();
            String base = "";
            if (j != null) {
                base = Util.removeTrailingSlash(j.getRootUrl()) + "/";
            }
            return base + r.getUrl();
        }

        @Override public String toString() {
            return "PlaceholderExecutable:" + PlaceholderTask.this;
        }

        private static final long serialVersionUID = 1L;

        @Override
        public ACL getACL() {
            return getParent().getACL();
        }

        @Override
        public void checkPermission(@Nonnull Permission permission) throws AccessDeniedException {
            getACL().checkPermission(permission);
        }

        @Override
        public boolean hasPermission(@Nonnull Permission permission) {
            return getACL().hasPermission(permission);
        }
    }

    @ExportedBean
    public static class ExecutableImpl implements Queue.Executable {
        private final SubTask parent;

        private ExecutableImpl(SubTask parent) {
            this.parent = parent;
        }

        @Nonnull
        public SubTask getParent() {
            return parent;
        }

        public void run() {
            // nothing. we just waste time
        }

        @Override public long getEstimatedDuration() {
            return parent.getEstimatedDuration();
        }

        @Exported
        public String getDisplayName() {
            return parent.getDisplayName();
        }

        @Exported
        public String getFullDisplayName() {
            return parent.getDisplayName();
        }
    }

    private static final long serialVersionUID = 1098885580375315588L; // as of 2.12

    private static final Logger LOGGER = Logger.getLogger(PlaceholderTask.class.getName());
}
