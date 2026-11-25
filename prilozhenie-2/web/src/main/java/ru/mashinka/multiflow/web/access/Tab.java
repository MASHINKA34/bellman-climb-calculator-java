package ru.mashinka.multiflow.web.access;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// вкладки, к которым ключ даёт доступ. Имена те же, что data-tab на странице
public enum Tab {

    FLOW("flow"),
    PLACEMENT("placement"),
    TRANSPORT("transport");

    private final String id;

    Tab(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public static Tab fromId(String id) {
        return Arrays.stream(values())
                .filter(tab -> tab.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Неизвестная вкладка: " + id));
    }

    public static Set<Tab> fromIds(Collection<String> ids) {
        Set<Tab> tabs = EnumSet.noneOf(Tab.class);
        ids.forEach(id -> tabs.add(fromId(id)));
        return tabs;
    }

    // в порядке вкладок на странице
    public static List<String> ids(Set<Tab> tabs) {
        return tabs.stream().sorted().map(Tab::getId).collect(Collectors.toList());
    }
}
