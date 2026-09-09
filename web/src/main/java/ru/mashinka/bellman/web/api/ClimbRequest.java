package ru.mashinka.bellman.web.api;

import ru.mashinka.bellman.core.model.Aircraft;
import ru.mashinka.bellman.core.model.ClimbTask;

/**
 * Тело запроса на расчёт: данные самолёта и условия задачи.
 * Отсутствующий раздел заменяется значениями по умолчанию.
 */
public class ClimbRequest {

    private Aircraft aircraft = Aircraft.defaultAirliner();
    private ClimbTask task = ClimbTask.defaultTask();

    public Aircraft getAircraft() {
        return aircraft;
    }

    public void setAircraft(Aircraft aircraft) {
        this.aircraft = aircraft != null ? aircraft : Aircraft.defaultAirliner();
    }

    public ClimbTask getTask() {
        return task;
    }

    public void setTask(ClimbTask task) {
        this.task = task != null ? task : ClimbTask.defaultTask();
    }
}
