package ru.mashinka.multiflow.core.gomoryhu;

// ветвь дерева на промежуточном шаге: соединяет две группы вершин
// a и b - номера групп в списке групп дерева, value - поток, step - на каком шаге появилась
public final class GroupEdge {

    private final int a;
    private final int b;
    private final int value;
    private final int step;

    GroupEdge(int a, int b, int value, int step) {
        this.a = a;
        this.b = b;
        this.value = value;
        this.step = step;
    }

    public int getA() {
        return a;
    }

    public int getB() {
        return b;
    }

    public int getValue() {
        return value;
    }

    public int getStep() {
        return step;
    }

    boolean touches(int group) {
        return a == group || b == group;
    }

    int other(int group) {
        return a == group ? b : a;
    }

    // та же ветвь, но вместо группы from к ней теперь подвешена группа to
    GroupEdge reattach(int from, int to) {
        return a == from
                ? new GroupEdge(to, b, value, step)
                : new GroupEdge(a, to, value, step);
    }
}
