package ru.mashinka.multiflow.web.access;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// расчёт доступен только с ключом, в который входит эта вкладка (проверяет AccessInterceptor).
// Отметка стоит на самом методе, а не на адресе: сколько ни меняй запись адреса (%73olve, лишние
// косые), до метода без проверки не дойти
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresTab {

    Tab value();
}
