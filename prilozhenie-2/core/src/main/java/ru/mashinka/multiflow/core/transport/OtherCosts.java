package ru.mashinka.multiflow.core.transport;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import ru.mashinka.multiflow.core.transport.TransportProblem.Balanced;

// метод минимальной стоимости при равных тарифах даёт разные планы - смотря какую клетку взять.
// Здесь перебираются все такие выборы и собираются все возможные общие стоимости, чтобы верный
// ответ с другим выбором не приняли за ошибку.
// Базисные нули на стоимость не влияют, поэтому исчерпанные строки и столбцы просто убираются.
final class OtherCosts {

    // на больших таблицах с одинаковыми тарифами выборов слишком много - перебор обрывается
    static final int MAX_STATES = 20_000;

    private final Balanced table;
    private final boolean fictitiousLast;
    private final Set<String> seen = new HashSet<>();
    private final TreeSet<Long> costs = new TreeSet<>();
    private boolean truncated;

    private OtherCosts(Balanced table, boolean fictitiousLast) {
        this.table = table;
        this.fictitiousLast = fictitiousLast;
    }

    static OtherCosts search(Balanced table, boolean fictitiousLast) {
        OtherCosts search = new OtherCosts(table, fictitiousLast);
        search.visit(table.getSupply(), table.getDemand(), 0);
        return search;
    }

    // все найденные стоимости по возрастанию
    TreeSet<Long> costs() {
        return costs;
    }

    boolean isTruncated() {
        return truncated;
    }

    // каждый ход убирает хотя бы одну строку или столбец, поэтому глубина - не больше m + n
    private void visit(long[] supply, long[] demand, long cost) {
        if (seen.size() >= MAX_STATES) {
            truncated = true;
            return;
        }
        if (!seen.add(Arrays.toString(supply) + Arrays.toString(demand) + cost)) {
            return;
        }
        long best = Long.MAX_VALUE;
        for (int i = 0; i < supply.length; i++) {
            for (int j = 0; j < demand.length; j++) {
                if (supply[i] > 0 && demand[j] > 0) {
                    best = Math.min(best, InitialPlans.rank(table, i, j, fictitiousLast));
                }
            }
        }
        if (best == Long.MAX_VALUE) {
            costs.add(cost);
            return;
        }
        for (int i = 0; i < supply.length; i++) {
            for (int j = 0; j < demand.length; j++) {
                if (supply[i] > 0 && demand[j] > 0
                        && InitialPlans.rank(table, i, j, fictitiousLast) == best) {
                    long value = Math.min(supply[i], demand[j]);
                    long[] supplyLeft = supply.clone();
                    long[] demandLeft = demand.clone();
                    supplyLeft[i] -= value;
                    demandLeft[j] -= value;
                    visit(supplyLeft, demandLeft, cost + value * table.cost(i, j));
                }
            }
        }
    }
}
