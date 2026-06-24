package tech.kayys.gamelan.scheduler;

import tech.kayys.gamelan.scheduler.contract.TaskDeadLetterQueueContract;

class InMemoryTaskDeadLetterQueueTest implements TaskDeadLetterQueueContract {

    @Override
    public TaskDeadLetterQueue newTaskDeadLetterQueue() {
        return new InMemoryTaskDeadLetterQueue();
    }
}
