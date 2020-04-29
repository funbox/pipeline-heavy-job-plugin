package ru.funbox.jenkins.plugins.pipelineheavyjob;

import hudson.Extension;
import hudson.model.TaskListener;
import jenkins.YesNoMaybe;
import org.jenkinsci.plugins.workflow.steps.Step;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepDescriptor;
import org.jenkinsci.plugins.workflow.steps.StepExecution;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collections;
import java.util.Set;

public class NodeWithWeightStep extends Step implements Serializable {
    private static final long serialVersionUID = 1L;

    private int weight = 1;
    private @CheckForNull String label;

    @DataBoundConstructor
    public NodeWithWeightStep() {
    }

    public @CheckForNull
    String getLabel() {
        return label;
    }

    @DataBoundSetter
    public void setLabel(@Nullable final String label) {
        this.label = label;
    }

    public int getWeight() {
        return weight;
    }

    @DataBoundSetter
    public void setWeight(int weight) {
        this.weight = weight;
    }

    @Override
    public StepExecution start(StepContext context) {
        return new NodeWithWeightStepExecution(context, this);
    }

    @Extension(dynamicLoadable = YesNoMaybe.YES, optional = true)
    public static class DescriptorImpl extends StepDescriptor {

        @Override
        public Set<? extends Class<?>> getRequiredContext() {
            return Collections.singleton(TaskListener.class);
        }

        @Override
        public String getFunctionName() {
            return "nodeWithWeight";
        }

        @Override
        public boolean takesImplicitBlockArgument() {
            return true;
        }
    }
}
