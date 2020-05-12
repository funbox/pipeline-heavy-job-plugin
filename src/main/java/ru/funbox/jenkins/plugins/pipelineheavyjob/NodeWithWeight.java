package ru.funbox.jenkins.plugins.pipelineheavyjob;

import hudson.Extension;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgent;
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgentDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public class NodeWithWeight extends DeclarativeAgent<NodeWithWeight> {
    private String label;
    private int weight;

    @DataBoundConstructor
    public NodeWithWeight() {
    }

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

    @Extension(ordinal = 1000) @Symbol("nodeWithWeight")
    public static class DescriptorImpl extends DeclarativeAgentDescriptor<NodeWithWeight> {
        @Override
        @Nonnull
        public String getDisplayName() {
            return "Run on an agent matching a label with specified weight";
        }
    }
}
