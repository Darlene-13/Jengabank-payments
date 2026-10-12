package com.jengabank.payments.fraud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Synchronous rules engine: every transfer is evaluated before it is saved.
 *
 * <p>Verdicts: {@code ALLOW} (complete now), {@code REVIEW} (hold for a second
 * pair of eyes), {@code BLOCK} (persist as evidence, reject). The worst rule
 * wins, so a single BLOCK cannot be outvoted by lenient rules.
 */
@Service
public class FraudEngine {

    private final List<FraudRule> rules;

    public FraudEngine(List<FraudRule> rules) {
        this.rules = rules.stream()
                .sorted(Comparator.comparing(FraudRule::code))
                .collect(Collectors.toList());
    }

    public FraudVerdict evaluate(FraudContext context) {
        FraudDecision worst = FraudDecision.ALLOW;
        List<String> reasons = new ArrayList<>();
        for (FraudRule rule : rules) {
            FraudFinding finding = rule.check(context);
            if (finding == null) {
                continue;
            }
            reasons.add(rule.code() + ":" + finding.reason());
            if (finding.decision().worseThan(worst)) {
                worst = finding.decision();
            }
        }
        return new FraudVerdict(worst, List.copyOf(reasons));
    }

    public List<FraudRule> rules() {
        return List.copyOf(rules);
    }
}
