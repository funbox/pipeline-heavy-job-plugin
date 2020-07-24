# Pipeline HeavyJob Plugin

This plugin allows defining job weight for pipeline projects as
[HeavyJob Plugin](https://github.com/jenkinsci/heavy-job-plugin) does for
freestyle projects.

[По-русски](./README.ru.md)

## Usage

Pipeline:

```groovy
nodeWithWeight(label: 'nodejs', weight: 2) {
    echo 'Run heavy tests'
}
```

Pipeline Declarative Syntax:

```groovy
pipeline {
    agent {
        nodeWithWeight {
            label 'nodejs'
            weight 2
        }
    }
    stages {
        stage('Build') {
            steps {
                echo 'Run heavy build'
            }
        }
    }
}
```

## Rationale

Typical Jenkins setup consists of the main server with several worker nodes.
Each node has several slots. By default one build occupies one slot. Some builds
will wait in a queue if there are no more free slots available.

Each node has a limited amount of resources like CPU, RAM, or network. Different
builds require different amount of these resources but each build occupies only
one slot. That is why in some cases one node can be overloaded and another
almost idle.

There are several ways to solve such a problem.

Suppose we have two types of builds: light and heavy. Heavy build requires twice
twice as much resources as light one. Suppose we have two worker nodes. Each
node has enough resources to run two parallel light builds or one heavy.

We want to run as many builds as possible but do it in a such a way that none of
the nodes will be overloaded.

### Separate worker for heavy builds

The idea is to configure Jenkins setup in such a way:

* worker 1 should have 2 slots and perform only light builds;
* worker 2 should have 1 slot and perform light and heavy builds.

Pros: workers will never be overloaded.
Cons: in some cases worker 2 will utilize only half of the available resources.

### lockable-resources

Configure 4 resources named “slot” using
[lockable-resources](https://www.jenkins.io/doc/pipeline/steps/lockable-resources/).
For light build lock one “slot” resource:

```groovy
lock(label: 'slot', quantity: 1) {
    echo 'Perform light build'
}
```
For heavy build — lock 2 resources:

```groovy
lock(label: 'slot', quantity: 2) {
    echo 'Perform heavy build'
}
```

Pros: workers will run as many builds as possible.
Cons: in some cases workers can be overloaded.

### Freestyle projects

It's possible to use freestyle projects with
[HeavyJob Plugin](https://github.com/jenkinsci/heavy-job-plugin) which solves
the problem completely.

Pros: workers will run as many builds as possible, workers won't be overloaded.
Cons: we can't use configuration as a code.

### Conclustion

Each described solution has pros and cons and it will be very useful to have
something like HeavyJob Plugin but for pipeline projects. As I've found in
[Jenkins Jira](https://issues.jenkins-ci.org/browse/JENKINS-41940) there are no
such a plugin that is why Pipeline HeavyJob Plugin was developed.

## Implementation details

This plugin implements `nodeWithWeight` step and `nodeWithWeight` agent based on
it. The code of the plugin is base on the code of
[workflow-durable-task-step-plugin](https://github.com/jenkinsci/workflow-durable-task-step-plugin).

[![Sponsored by FunBox](https://funbox.ru/badges/sponsored_by_funbox_centered.svg)](https://funbox.ru)
