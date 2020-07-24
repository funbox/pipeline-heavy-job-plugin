# Pipeline HeavyJob Plugin

Плагин для Jenkins, который позволяет указывать вес того или иного шага в
Pipeline-проектах, как это делает
[HeavyJob Plugin](https://github.com/jenkinsci/heavy-job-plugin) во
Freestyle проектах.

## Пример использования

Pipeline-команда:

```groovy
nodeWithWeight(label: 'nodejs', weight: 2) {
    echo 'Run heavy tests'
}
```

Pipeline Declarative синтаксис:

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

## Запуск плагина в процессе разработки

1. Активировать Java 1.8.
2. Запустить `mvn hpi:run`.

Инстанс Jenkins с установленным плагином будет доступен по адресу
http://localhost:8080/jenkins.

Для запуска тестов можно воспользоваться командой `mvn test` или средствами
IDE.

## Мотивация

CI инфраструктура представляет собой Jenkins-сервер у которого есть несколько
slave-узлов, выполняющих разные задачи. Каждый slave-узел имеет некоторое
количество слотов, определяющее максимальное количество одновременно
обрабатываемых задач. Если задач больше, чем свободных слотов, то часть задач
ожидает в очереди.

Каждый узел имеет ограниченное количество таких ресурсов как CPU, память,
жесткий диск или сеть. Различные задачи потребляют различное количество
ресурсов: например, сборка небольшого фронтенд-проекта может требовать гораздо
меньше памяти, чем прогон тестов большого бекенд-проекта. С другой стороны
по умолчанию каждый проект занимает ровно один слот. Таким образом на одном и
том же slave-узле могут быть одновременно запущены несколько тяжелых проектов
или несколько легких, поэтому иногда возникает ситуация, когда узел перегружен
и все задачи выполняются очень медленно или завершаются с ошибкой.

Есть несколько способов выхода из данной ситуации, у каждого из которых есть
свои плюсы и минусы:

* создать отдельный slave-узел для тяжелых задач и отдельный для легких;
* использовать плагин
    [lockable-resources](https://www.jenkins.io/doc/pipeline/steps/lockable-resources/);
* использовать Freestyle проекты;
* использовать workaround на Groovy.

Предположим, что существуют два типа задач: тяжелые и легкие. Тяжелые требуют
в два раза больше ресурсов чем легкие. Есть два slave-узла по два слота на
каждом. Каждый слот соответствует количеству ресурсов, необходимому для
комфортной обработки легкой задачи.

Задача: необходимо настроить запуск задач таким образом, чтобы не перегружать
slave-узлы.

Рассмотрим подробнее каждый из предложенных выше вариантов для решения этой
задачи.

### Отдельный Slave для тяжелых задач

Считаем что slave-1 будет использоваться только для легких задач и иметь два
слота, а slave-2 будет использоваться как для легких, так и для тяжелых задач,
но при этом будет иметь только один слот.

**Плюсы**: slave-узлы не перегружены.

**Минусы**: в некоторых случаях задачи выполняются не так эффективно как могли
бы. Например, можно запустить максимум три легких задачи или одну тяжелую,
вместо четырех легких и двух тяжелых.

### Плагин lockable-resources

С помощью плагина
[lockable-resources](https://www.jenkins.io/doc/pipeline/steps/lockable-resources/)
настраиваются четыре ресурса с названием «slot». В легкой задаче блокируется
один слот на время обработки задачи:

```groovy
lock(label: 'slot', quantity: 1) {
    echo "Process light task"
}
```

В тяжелой задаче блокируется два слота на время обработки задачи:

```groovy
lock(label: 'slot', quantity: 2) {
    echo "Process heavy task"
}
```

**Плюсы**: slave-узлы утилизируются по максимуму.

**Минусы**: в некоторых случаях slave-узлы перегружены (допустим на slave-1
запущена легкая задача и на slave-2 запущена легкая задач, при попытке
запустить тяжелую задачу она запустится, так как доступно два слота, при этом
один из узлов будет перегружен).

### Freestyle проекты

Можно не использовать Pipeline проекты и ограничиться Freestyle проектами с
[HeavyJob Plugin](https://github.com/jenkinsci/heavy-job-plugin).

**Плюсы**: slave-узлы утилизируются по-максимуму, но не перегружаются.

**Минусы**: невозможно описывать конфигурацию в коде.

### Workaround на Groovy

Можно использовать такой хак для занятия нужного количества слотов:

```groovy
def multiSlotNode_(String label, int slots = 1, java.util.concurrent.atomic.AtomicInteger nodeAcquired, Closure body) {
    if (slots == 1) {
        node(label) {
            nodeAcquired.addAndGet(1)
            body()
        }
    } else if (slots > 1) {
        node(label) {
            nodeAcquired.addAndGet(1)
            multiSlotNode_(env.NODE_NAME, slots - 1, nodeAcquired, body)
        }
    } else {
        throw new IllegalArgumentException("Number of slots must be greather than zero")
    }
}

def multiSlotNode(String label, int slots = 1, Closure body) {

    def nodeAcquired = new java.util.concurrent.atomic.AtomicInteger(0)

    timestamps {
        def rerun = true
        while (rerun) {

            try {
                parallel (
                    failFast: true,

                    "timer" : {
                        timeout(time: 5, unit: 'SECONDS') {
                            waitUntil {
                                return nodeAcquired.get() >= slots
                            }
                        }
                    },

                    "job" : {
                        nodeAcquired.set(0)
                        rerun = false
                        multiSlotNode_(label, slots, nodeAcquired, body)
                    }
                )
            } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException ex) {
                // Timeout to get available nodes. Trying again later
                sleep time: 60, unit: 'SECONDS'
                rerun = true
            }
        }
    }
}

multiSlotNode('', 4, {sleep(time: 10, unit: 'SECONDS'); println "HELLO WORLD"} )
```

Плюсы и минусы такие же как и у варианта с плагином lockable-resources.

### Вывод

Хотелось бы иметь все преимущества Heavy Job Plugin в Pipeline-проектах. Судя
по [тикету в Jira Jenkins](https://issues.jenkins-ci.org/browse/JENKINS-41940)
такое поведение интересно многим разработчикам, но, к сожалению, до сих пор не
реализовано. Данный плагин решает поставленную задачу.

[![Sponsored by FunBox](https://funbox.ru/badges/sponsored_by_funbox_centered.svg)](https://funbox.ru)
