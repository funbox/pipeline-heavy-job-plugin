package ru.funbox.jenkins.plugins.pipelineheavyjob

import org.jenkinsci.plugins.pipeline.modeldefinition.agent.DeclarativeAgentScript
import org.jenkinsci.plugins.workflow.cps.CpsScript
import org.jenkinsci.plugins.pipeline.modeldefinition.agent.CheckoutScript

class NodeWithWeightScript extends DeclarativeAgentScript<NodeWithWeight> {

    NodeWithWeightScript(CpsScript s, NodeWithWeight a) {
        super(s, a);
    }

    @Override
    Closure run(Closure body) {
        return {
            script.nodeWithWeight(label: describable?.label, weight: describable?.weight) {
                CheckoutScript.doCheckout(script, describable, null, body).call()
            }
        }
    }
}
