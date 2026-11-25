package ru.mashinka.multiflow.core.transport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import ru.mashinka.multiflow.core.transport.TransportProblem.Balanced;
import ru.mashinka.multiflow.core.transport.TransportStep.Crossed;

// начальный опорный план транспортной задачи двумя методами.
//
// Северо-западный угол: каждый раз берётся левая верхняя из невычеркнутых клеток.
// Минимальная стоимость: каждый раз берётся клетка с наименьшим тарифом из невычеркнутых.
// Если таких несколько - та, куда помещается больше груза, из равных - верхняя, затем левая.
// Нулевые тарифы фиктивной строки или столбца берутся в последнюю очередь либо наравне
// с остальными - это решает пользователь.
//
// В клетку пишется наименьшее из остатка запаса и остатка потребности, исчерпанная строка или
// столбец вычёркивается. Если закончились оба сразу, вычёркивается только одно, а во втором
// остаётся 0: позже туда запишется перевозка 0 (базисный ноль). Поэтому заполненных клеток
// всегда ровно m + n - 1.
final class InitialPlans {

    private static final Logger LOG = Logger.getLogger(InitialPlans.class.getName());

    private InitialPlans() {
    }

    //#северо-западный-угол
    static TransportPlan northWest(Balanced table, String runId) {
        return build(table, runId, "northwest",
                state -> new int[][]{{firstFree(state.rowsCrossed), firstFree(state.columnsCrossed)}});
    }

    //#минимальная-стоимость
    static TransportPlan minimumCost(Balanced table, boolean fictitiousLast, String runId) {
        return build(table, runId, "mincost", state -> cheapest(table, state, fictitiousLast));
    }

    // все клетки с наименьшим тарифом; первая в списке - та, что выбрана
    private static int[][] cheapest(Balanced table, State state, boolean fictitiousLast) {
        List<int[]> best = new ArrayList<>();
        long bestRank = Long.MAX_VALUE;
        for (int i = 0; i < table.rows(); i++) {
            for (int j = 0; j < table.columns(); j++) {
                if (state.rowsCrossed[i] || state.columnsCrossed[j]) {
                    continue;
                }
                long rank = rank(table, i, j, fictitiousLast);
                if (rank < bestRank) {
                    bestRank = rank;
                    best.clear();
                }
                if (rank == bestRank) {
                    best.add(new int[]{i, j});
                }
            }
        }
        //#ничья - из равных тарифов наибольшая перевозка; обход уже идёт сверху вниз и слева направо
        int chosen = 0;
        for (int k = 1; k < best.size(); k++) {
            if (state.room(best.get(k)) > state.room(best.get(chosen))) {
                chosen = k;
            }
        }
        best.add(0, best.remove(chosen));
        return best.toArray(new int[0][]);
    }

    // очередь клетки: её тариф; фиктивные клетки при fictitiousLast - после всех настоящих
    static long rank(Balanced table, int row, int column, boolean fictitiousLast) {
        boolean later = fictitiousLast && table.isFictitious(row, column);
        return later ? (long) TransportProblem.MAX_COST + 1 : table.cost(row, column);
    }

    private static TransportPlan build(Balanced table, String runId, String method, Choice choice) {
        int rows = table.rows();
        int columns = table.columns();
        State state = new State(table);
        long[][] amount = new long[rows][columns];
        boolean[][] filled = new boolean[rows][columns];
        List<TransportStep> steps = new ArrayList<>();

        //#шаги - каждый вычёркивает одну строку или столбец, последний - сразу обе: всего m + n - 1
        int total = rows + columns - 1;
        for (int index = 1; index <= total; index++) {
            int[][] candidates = choice.candidates(state);
            int row = candidates[0][0];
            int column = candidates[0][1];
            long value = state.room(candidates[0]);
            amount[row][column] = value;
            filled[row][column] = true;
            state.supply[row] -= value;
            state.demand[column] -= value;

            boolean rowDone = state.supply[row] == 0;
            boolean columnDone = state.demand[column] == 0;
            Crossed crossed;
            if (index == total) {
                crossed = Crossed.BOTH;
            } else if (rowDone && columnDone) {
                //#вырожденность - вычёркивается строка; если она последняя, то столбец
                crossed = free(state.rowsCrossed) > 1 ? Crossed.ROW : Crossed.COLUMN;
            } else {
                crossed = rowDone ? Crossed.ROW : Crossed.COLUMN;
            }
            if (crossed != Crossed.COLUMN) {
                state.rowsCrossed[row] = true;
            }
            if (crossed != Crossed.ROW) {
                state.columnsCrossed[column] = true;
            }
            boolean degenerate = rowDone && columnDone && crossed != Crossed.BOTH;
            TransportStep step = new TransportStep(index, row, column, value, table.cost(row, column), candidates,
                    crossed, degenerate, state.supply, state.demand, state.rowsCrossed, state.columnsCrossed);
            steps.add(step);
            LOG.fine(() -> String.format(Locale.ROOT,
                    "run=%s %s step %d: cell (%d,%d) tariff %d, amount %d, crossed %s%s, equal cells %d, "
                            + "supply left %s, demand left %s",
                    runId, method, step.getIndex(), step.getRow() + 1, step.getColumn() + 1, step.getCost(),
                    step.getAmount(), step.getCrossed().getId(), step.isDegenerate() ? " (degenerate)" : "",
                    candidates.length, Arrays.toString(step.getSupplyAfter()),
                    Arrays.toString(step.getDemandAfter())));
        }
        TransportPlan plan = new TransportPlan(amount, filled, table.getCost(), steps);
        LOG.info(() -> String.format(Locale.ROOT, "run=%s %s done: cost %d, %d filled cells, %d basic zeros",
                runId, method, plan.getCost(), plan.filledCount(), plan.zeroCount()));
        return plan;
    }

    private static int firstFree(boolean[] crossed) {
        for (int k = 0; k < crossed.length; k++) {
            if (!crossed[k]) {
                return k;
            }
        }
        throw new IllegalStateException("Все строки или столбцы вычеркнуты раньше времени");
    }

    private static int free(boolean[] crossed) {
        int count = 0;
        for (boolean value : crossed) {
            if (!value) {
                count++;
            }
        }
        return count;
    }

    private interface Choice {
        // клетки на выбор; берётся первая
        int[][] candidates(State state);
    }

    // остатки и вычеркнутые строки и столбцы
    private static final class State {
        private final long[] supply;
        private final long[] demand;
        private final boolean[] rowsCrossed;
        private final boolean[] columnsCrossed;

        private State(Balanced table) {
            supply = table.getSupply();
            demand = table.getDemand();
            rowsCrossed = new boolean[supply.length];
            columnsCrossed = new boolean[demand.length];
        }

        // сколько груза помещается в клетку
        private long room(int[] cell) {
            return Math.min(supply[cell[0]], demand[cell[1]]);
        }
    }
}
