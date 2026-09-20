package forge.ai.simulation;

import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCost;
import forge.ai.simulation.GameStateEvaluator.Score;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import org.apache.commons.math3.util.CombinatoricsUtils;

import java.util.*;

public class SpellAbilityChoicesIterator {
    private final SimulationController controller;

    private Iterator<int[]> modeIterator;
    private int[] selectedModes;
    private Score bestScoreForMode = new Score(Integer.MIN_VALUE);
    private boolean advancedToNextMode;

    private ArrayList<Score> cachedTargetScores;
    private int nextTarget = 0;
    private Score bestScoreForTarget = new Score(Integer.MIN_VALUE);
    private boolean pushTarget = true;

    private static class ChoicePoint {
        int numChoices = -1;
        int nextChoice = 0;
        Card selectedChoice;
        Score bestScoreForChoice = new Score(Integer.MIN_VALUE);
        // True while this choice's evaluation level is on the controller's stack, i.e. chooseCard()
        // pushed it for the current simulation. The simulator may bail out before reaching this
        // choice again (e.g. "SA not found" on the copy) - then the level was never re-pushed and
        // advance() must not pop it.
        boolean open;
    }
    private final ArrayList<ChoicePoint> choicePoints = new ArrayList<>();
    private int incrementedCpIndex = 0;
    private int cpIndex = -1;

    private int evalDepth;
    // Maps from filtered mode indexes to original ones.
    private List<Integer> modesMap;

    public SpellAbilityChoicesIterator(SimulationController controller) {
        this.controller = controller;
    }

    public List<AbilitySub> chooseModesForAbility(SpellAbility sa, List<AbilitySub> choices, int min, int num, boolean allowRepeat) {
        if (modeIterator == null) {
            // Skip modes that don't have legal targets.
            modesMap = new ArrayList<>();
            int origIndex = -1;
            for (AbilitySub sub : choices) {
                origIndex++;
                if (!ComputerUtilAbility.isFullyTargetable(sub)) {
                    continue;
                }
                modesMap.add(origIndex);
            }
            // TODO: Do we need to do something special to support cards that have extra costs
            // when choosing more modes, like Blessed Alliance?
            if (modesMap.isEmpty()) {
                return null;
            } else if (!allowRepeat) {
                modeIterator = CombinatoricsUtils.combinationsIterator(modesMap.size(), num);
            } else {
                // Note: When allowRepeat is true, it does result in many possibilities being tried.
                // We should ideally prune some of those at a higher level.
                modeIterator = new AllowRepeatModesIterator(modesMap.size(), min, num);
            }
            selectedModes = remapModes(modeIterator.next());
            advancedToNextMode = true;
        }
        // Note: If modeIterator already existed, selectedModes would have been updated in advance().
        List<AbilitySub> result = getModeCombination(choices, selectedModes);
        if (advancedToNextMode) {
            StringBuilder sb = new StringBuilder();
            for (AbilitySub sub : result) {
                if (sb.length() > 0) {
                    sb.append(" ");
                } else {
                    sb.append(sub.getHostCard().getName()).append(" -> ");
                }
                sb.append(sub);
            }
            controller.evaluateChosenModes(selectedModes, sb.toString());
            evalDepth++;
            advancedToNextMode = false;
        }
        return result;
    }

    private int[] remapModes(int[] modes) {
        for (int i = 0; i < modes.length; i++) {
            modes[i] = modesMap.get(modes[i]);
        }
        return modes;
    }

    public Card chooseCard(CardCollection fetchList) {
        cpIndex++;
        if (cpIndex >= choicePoints.size()) {
            choicePoints.add(new ChoicePoint());
        }
        ChoicePoint cp = choicePoints.get(cpIndex);
        // Prune duplicates.
        HashSet<String> uniqueCards = new HashSet<>();
        for (Card card : fetchList) {
            if (uniqueCards.add(card.getName()) && uniqueCards.size() == cp.nextChoice + 1) {
                cp.selectedChoice = card;
            }
        }
        if (cp.selectedChoice == null) {
            throw new RuntimeException();
        }
        cp.numChoices = uniqueCards.size();
        if (cpIndex >= incrementedCpIndex) {
            controller.evaluateCardChoice(cp.selectedChoice);
            evalDepth++;
            cp.open = true;
        }
        return cp.selectedChoice;
    }

    public void chooseTargets(SpellAbility sa, GameSimulator simulator) {
        // Note: Can't just keep a TargetSelector object cached because it's
        // responsible for setting state on a SA and the SA object changes each
        // time since it's a different simulation.
        MultiTargetSelector selector = new MultiTargetSelector(sa, null);
        if (selector.hasPossibleTargets()) {
            if (cachedTargetScores == null) {
                cachedTargetScores = new ArrayList<>();
                nextTarget = -1;
                for (int i = 0; selector.selectNextTargets(); i++) {
                    Score score = controller.shouldSkipTarget(sa, simulator);
                    cachedTargetScores.add(score);
                    if (score != null) {
                        controller.printState(score, sa, " - via estimate (skipped)", false);
                    } else if (nextTarget == -1) {
                        nextTarget = i;
                    }
                }
                selector.reset();
                // If all targets were cached, we unfortunately have to evaluate the first target again
                // because at this point we're already running the simulation code and there's no turning
                // back. This used to be not possible when the PossibleTargetSelector was controlling the
                // flow. :(
                if (nextTarget == -1) { nextTarget = 0; }
            }
            selector.selectTargetsByIndex(nextTarget);
            controller.setHostAndTarget(sa, simulator);
            // The hierarchy is modes -> targets -> choices[]. In the presence of choices, we want to call
            // evaluate just once at the top level.
            if (pushTarget) {
                controller.evaluateTargetChoices(sa, selector.getLastSelectedTargets());
                evalDepth++;
                pushTarget = false;
            }
        }
    }

    public boolean advance(Score lastScore) {
        cpIndex = -1;
        for (ChoicePoint cp : choicePoints) {
            if (lastScore.value > cp.bestScoreForChoice.value) {
                cp.bestScoreForChoice = lastScore;
            }
        }
        if (lastScore.value > bestScoreForTarget.value) {
            bestScoreForTarget = lastScore;
        }
        if (lastScore.value > bestScoreForMode.value) {
            bestScoreForMode = lastScore;
        }

        // Once the time budget is exhausted, no further choices, targets or modes are
        // tried: every level is treated as exhausted, so that the bookkeeping below still
        // unwinds and the best result seen so far is kept.
        final boolean outOfTime = controller.isOutOfTime();

        if (!choicePoints.isEmpty()) {
            for (int i = choicePoints.size() - 1; i >= 0 && !outOfTime; i--) {
                ChoicePoint cp = choicePoints.get(i);
                if (cp.nextChoice + 1 < cp.numChoices) {
                    cp.nextChoice++;
                    // Remove tail of the list.
                    incrementedCpIndex = i;
                    for (int j = choicePoints.size() - 1; j >= i; j--) {
                        popChoicePoint(choicePoints.get(j));
                    }
                    choicePoints.subList(i + 1, choicePoints.size()).clear();
                    return true;
                }
            }
            for (int i = choicePoints.size() - 1; i >= 0; i--) {
                popChoicePoint(choicePoints.get(i));
            }
            choicePoints.clear();
        }
        if (cachedTargetScores != null) {
            // The target level is only open if chooseTargets() ran in the last simulation.
            // It doesn't when the simulator bails out early (e.g. "SA not found" on the copy),
            // and popping an unopened level would unbalance evalDepth.
            if (!pushTarget) {
                doneEvaluating(bestScoreForTarget);
            }
            pushTarget = true;
            bestScoreForTarget = new Score(Integer.MIN_VALUE);
            while (!outOfTime && nextTarget + 1 < cachedTargetScores.size()) {
                nextTarget++;
                if (cachedTargetScores.get(nextTarget) == null) {
                    return true;
                }
            }
            nextTarget = -1;
            cachedTargetScores = null;
        }
        if (modeIterator != null) {
            // Same guard for modes: advancedToNextMode is reset by chooseModesForAbility() when
            // the level was actually pushed for the last simulation.
            if (!advancedToNextMode) {
                doneEvaluating(bestScoreForMode);
            }
            bestScoreForMode = new Score(Integer.MIN_VALUE);
            if (!outOfTime && modeIterator.hasNext()) {
                selectedModes = remapModes(modeIterator.next());
                advancedToNextMode = true;
                return true;
            }
            modeIterator = null;
        }

        if (evalDepth != 0) {
            // A bookkeeping imbalance must not take the whole game down with it: report it and
            // rebalance the controller's score stack so the outer evaluation can continue.
            System.err.println("SpellAbilityChoicesIterator: unbalanced evalDepth " + evalDepth);
            while (evalDepth > 0) {
                doneEvaluating(new Score(Integer.MIN_VALUE));
            }
            evalDepth = 0;
        }
        return false;
    }

    private void popChoicePoint(ChoicePoint cp) {
        if (cp.open) {
            doneEvaluating(cp.bestScoreForChoice);
            cp.open = false;
        }
    }

    private void doneEvaluating(Score bestScore) {
        controller.doneEvaluating(bestScore);
        evalDepth--;
    }

    public static List<AbilitySub> getModeCombination(List<AbilitySub> choices, int[] modeIndexes) {
        ArrayList<AbilitySub> modes = new ArrayList<>();
        for (int modeIndex : modeIndexes) {
            modes.add(choices.get(modeIndex));
        }
        return modes;
    }

    public void announceX(SpellAbility sa) {
        // TODO this should also iterate over all possible values
        // (currently no additional complexity to keep performance reasonable)
        if (sa.costHasManaX()) {
            Integer x = ComputerUtilCost.setMaxXValue(sa, sa.getActivatingPlayer(), sa.isTrigger());
            controller.getLastDecision().xMana = x;
        }
    }

    private static class AllowRepeatModesIterator implements Iterator<int[]> {
        private final int numChoices;
        private final int max;
        private int[] indexes;

        public AllowRepeatModesIterator(int numChoices, int min, int max) {
            this.numChoices = numChoices;
            this.max = max;
            this.indexes = new int[min];
        }

        @Override
        public boolean hasNext() {
            return indexes != null;
        }

        // Note: This returns a new int[] array and doesn't modify indexes in place,
        // since that gets returned to the caller.
        private int[] getNextIndexes() {
            // TODO: In some cases, ordering has no effect - e.g. AAB and BAA are equivalent.
            // We should detect those and skip equivalent modes.
            for (int i = indexes.length - 1; i >= 0; i--) {
                if (indexes[i] < numChoices - 1) {
                    int[] nextIndexes = new int[indexes.length];
                    System.arraycopy(indexes, 0, nextIndexes, 0, i);
                    nextIndexes[i] = indexes[i] + 1;
                    return nextIndexes;
                }
            }
            if (indexes.length < max) {
                return new int[indexes.length + 1];
            }
            return null;
        }

        @Override
        public int[] next() {
            if (indexes == null) {
                throw new NoSuchElementException();
            }
            int[] result = indexes;
            indexes = getNextIndexes();
            return result;
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }
    }
}
