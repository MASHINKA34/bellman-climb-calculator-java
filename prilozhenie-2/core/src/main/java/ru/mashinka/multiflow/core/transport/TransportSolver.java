package ru.mashinka.multiflow.core.transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import ru.mashinka.multiflow.core.log.CoreLog;
import ru.mashinka.multiflow.core.transport.TransportProblem.Balanced;

// транспортная задача: закрытая модель, начальный план методами северо-западного угла и
// минимальной стоимости, оптимальный план для сверки
public class TransportSolver {

    private static final Logger LOG = Logger.getLogger(TransportSolver.class.getName());

    //#главный - точка входа, отсюда начинать показ
    public TransportSolution solve(TransportProblem problem, boolean fictitiousLast, String runId) {
        long startedAt = System.nanoTime();
        Balanced table = problem.balance();
        LOG.info(() -> String.format(Locale.ROOT,
                "run=%s transport start: %d suppliers, %d consumers, supply %d, demand %d, model %s (difference %d), "
                        + "fictitious cells %s",
                runId, problem.suppliers(), problem.consumers(), problem.getTotalSupply(), problem.getTotalDemand(),
                problem.type(), problem.fictitiousAmount(), fictitiousLast ? "last" : "by tariff"));

        TransportPlan northWest = InitialPlans.northWest(table, runId);
        TransportPlan minimumCost = InitialPlans.minimumCost(table, fictitiousLast, runId);

        OtherCosts search = OtherCosts.search(table, fictitiousLast);
        List<Long> others = new ArrayList<>(search.costs());
        others.remove(Long.valueOf(minimumCost.getCost()));

        OptimalPlan optimal = OptimalPlan.improve(table, minimumCost, runId);

        long elapsed = System.nanoTime() - startedAt;
        LOG.info(() -> String.format(Locale.ROOT,
                "run=%s transport done: north-west %d, minimum cost %d (other possible: %d%s), optimal %d, %s",
                runId, northWest.getCost(), minimumCost.getCost(), others.size(),
                search.isTruncated() ? ", search stopped at the limit" : "", optimal.getPlan().getCost(),
                CoreLog.millis(elapsed)));
        return new TransportSolution(problem, table, fictitiousLast, northWest, minimumCost, others,
                search.isTruncated(), optimal.getPlan(), optimal.isUnique(), elapsed);
    }
}
