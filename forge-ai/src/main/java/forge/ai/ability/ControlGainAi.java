/*
 * Forge: Play Magic: the Gathering.
 * Copyright (C) 2011  Forge Team
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package forge.ai.ability;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import forge.ai.*;
import forge.game.Game;
import forge.game.ability.AbilityUtils;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.card.CardLists;
import forge.game.card.CardPredicates;
import forge.game.cost.Cost;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.player.PlayerCollection;
import forge.game.player.PlayerPredicates;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.TargetRestrictions;
import forge.game.staticability.StaticAbilityMustTarget;
import forge.game.zone.ZoneType;
import forge.util.Aggregates;
import forge.util.collect.FCollectionView;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * AbilityFactory_GainControl class.
 * </p>
 * 
 * @author Forge
 * @version $Id: AbilityFactoryGainControl.java 17764 2012-10-29 11:04:18Z Sloth $
 */
public class ControlGainAi extends SpellAbilityAi {
    @Override
    protected AiAbilityDecision canPlay(final Player ai, final SpellAbility sa) {
        // Special card logic that is processed elsewhere
        if ("DonateTargetPerm".equals(sa.getParam("AILogic"))) {
            return SpecialCardAi.Donate.considerDonatingPermanent(ai, sa);
        }
        // "NewController" pointing at somebody else turns this around: the AI is not taking a
        // permanent, it is handing one of its own over. The rest of this method looks for the best
        // permanent among the opponents' and would never find a legal target here.
        if (sa.usesTargeting() && givesControlAway(ai, sa)) {
            return chooseDonation(ai, sa, false);
        }

        final List<String> lose = Lists.newArrayList();

        if (sa.hasParam("LoseControl")) {
            lose.addAll(Lists.newArrayList(sa.getParam("LoseControl").split(",")));
        }

        final TargetRestrictions tgt = sa.getTargetRestrictions();
        final Game game = ai.getGame();
        final PlayerCollection opponents = ai.getOpponents();

        // if Defined, then don't worry about targeting
        if (tgt == null) {
            if (sa.hasParam("AllValid")) {
                CardCollectionView tgtCards = opponents.getCardsIn(ZoneType.Battlefield);
                tgtCards = AbilityUtils.filterListByType(tgtCards, sa.getParam("AllValid"), sa);

                if (tgtCards.isEmpty()) {
                    return new AiAbilityDecision(0, AiPlayDecision.MissingNeededCards);
                }
            }
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        } else {
            sa.resetTargets();
            if (sa.hasParam("TargetingPlayer")) {
                Player targetingPlayer = AbilityUtils.getDefinedPlayers(sa.getHostCard(), sa.getParam("TargetingPlayer"), sa).get(0);
                sa.setTargetingPlayer(targetingPlayer);
                // TODO these blocks should continue checking with the worst
                // and if targetingPlayer is AI set the target directly (instead of using the Runnable)
                if (CardLists.getTargetableCards(ai.getGame().getCardsIn(sa.getTargetRestrictions().getZone()), sa).isEmpty()) {
                    return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
                }
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }

            if (tgt.canOnlyTgtOpponent()) {
                List<Player> oppList = opponents.filter(PlayerPredicates.isTargetableBy(sa));

                if (oppList.isEmpty()) {
                    return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
                }

                if (tgt.isRandomTarget()) {
                    sa.getTargets().add(Aggregates.random(oppList));
                } else {
                    sa.getTargets().add(oppList.get(0));
                }
            }
        }

        // Don't steal something if I can't Attack without, or prevent it from blocking at least
        if (lose.contains("EOT")
                && game.getPhaseHandler().getPhase().isAfter(PhaseType.COMBAT_DECLARE_ATTACKERS)
                && !sa.isTrigger()) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }

        if (sa.hasParam("Defined")) {
            // no need to target, we'll pick up the target from Defined
            return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
        }

        CardCollection list = opponents.getCardsIn(ZoneType.Battlefield);

        // Filter AI-specific targets if provided
        list = ComputerUtil.filterAITgts(sa, ai, list, false);

        // AI won't try to grab cards that are filtered out of AI decks on purpose
        list = CardLists.filter(list, c -> {
            if (!sa.canTarget(c)) {
                return false;
            }
            if (sa.isTrigger()) {
                return true;
            }

            if (!c.canBeControlledBy(ai)) {
                return false;
            }

            // do not take perm control on something that leaves the play end of turn
            if (!lose.contains("EOT") && c.hasSVar("EndOfTurnLeavePlay")) {
                return false;
            }

            if (c.isCreature()) {
                if (c.getNetCombatDamage() <= 0) {
                    return false;
                }

                // can not attack any opponent
                boolean found = false;
                for (final Player opp : opponents) {
                    if (ComputerUtilCombat.canAttackNextTurn(c, opp)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }

            // do not take control on something it doesn't know how to use
            return !ComputerUtilCard.isCardRemAIDeck(c);
        });

        if (list.isEmpty()) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }

        int creatures = 0, artifacts = 0, planeswalkers = 0, lands = 0, enchantments = 0;

        for (final Card c : list) {
            if (c.isCreature()) {
                creatures++;
            }
            if (c.isArtifact()) {
                artifacts++;
            }
            if (c.isLand()) {
                lands++;
            }
            if (c.isEnchantment()) {
                enchantments++;
            }
            if (c.isPlaneswalker()) {
                planeswalkers++;
            }
        }

        while (sa.canAddMoreTarget()) {
            Card t = null;

            if (list.isEmpty()) {
                if (sa.getTargets().size() < sa.getMinTargets() || sa.getTargets().size() == 0) {
                    sa.resetTargets();
                    return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
                } else {
                    // TODO is this good enough? for up to amounts?
                    break;
                }
            }

            // TODO check life of controller and consider stealing from another opponent so the risk of your army disappearing is spread out
            while (t == null) {
                // filter by MustTarget requirement
                CardCollection originalList = new CardCollection(list);

                list = CardLists.canSubsequentlyTarget(list, sa);

                boolean mustTargetFiltered = StaticAbilityMustTarget.filterMustTargetCards(ai, list, sa);

                if (planeswalkers > 0) {
                    t = ComputerUtilCard.getBestPlaneswalkerAI(list);
                } else if (creatures > 0) {
                    t = ComputerUtilCard.getBestCreatureAI(list);
                } else if (artifacts > 0) {
                    t = ComputerUtilCard.getBestArtifactAI(list);
                } else if (lands > 0) {
                    t = ComputerUtilCard.getBestLandAI(list);
                } else if (enchantments > 0) {
                    t = ComputerUtilCard.getBestEnchantmentAI(list, sa, false);
                } else {
                    t = ComputerUtilCard.getMostExpensivePermanentAI(list);
                }

                if (t != null) {
                    if (t.isCreature())
                        creatures--;
                    if (t.isPlaneswalker())
                        planeswalkers--;
                    if (t.isLand())
                        lands--;
                    if (t.isArtifact())
                        artifacts--;
                    if (t.isEnchantment())
                        enchantments--;
                }

                // Restore original list for next loop if filtered by MustTarget requirement
                if (mustTargetFiltered) {
                    list = originalList;
                }

                if (!sa.canTarget(t)) {
                    list.remove(t);
                    t = null;
                    if (list.isEmpty()) {
                        break;
                    }
                }
            }

            if (t != null) {
                sa.getTargets().add(t);
                list.remove(t);
            }
        }

        return new AiAbilityDecision(
                sa.isTargetNumberValid() ? 100 : 0,
                sa.isTargetNumberValid() ? AiPlayDecision.WillPlay : AiPlayDecision.TargetingFailed);
    }

    @Override
    protected AiAbilityDecision doTriggerNoCost(Player ai, SpellAbility sa, boolean mandatory) {
        if (sa.usesTargeting() && !"DonateTargetPerm".equals(sa.getParam("AILogic")) && givesControlAway(ai, sa)) {
            AiAbilityDecision donation = chooseDonation(ai, sa, mandatory);
            if (donation.willingToPlay() || !mandatory) {
                return donation;
            }
        }
        if (!sa.usesTargeting()) {
            if (mandatory) {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
        } else {
            if (sa.hasParam("TargetingPlayer") || (mandatory && !this.canPlay(ai, sa).willingToPlay())) {
                if (sa.getTargetRestrictions().canOnlyTgtOpponent()) {
                    List<Player> oppList = ai.getOpponents().filter(PlayerPredicates.isTargetableBy(sa));
                    if (oppList.isEmpty()) {
                        return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
                    }
                    sa.getTargets().add(Aggregates.random(oppList));
                    return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
                }

                List<Card> list = CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Battlefield), sa);
                if (list.isEmpty()) {
                    return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
                }
                sa.getTargets().add(ComputerUtilCard.getWorstAI(list));
            }
        }

        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    public AiAbilityDecision chkDrawback(final Player ai, SpellAbility sa) {
        final Game game = ai.getGame();

        // Special card logic that is processed elsewhere
        if (sa.hasParam("AILogic")) {
            if ("DonateTargetPerm".equals(sa.getParam("AILogic"))) {
                // Donate step 2 - target a donatable permanent.
                return SpecialCardAi.Donate.considerDonatingPermanent(ai, sa);
            }
        }

        if (!sa.usesTargeting()) {
            if (sa.hasParam("AllValid")) {
                CardCollectionView tgtCards = ai.getOpponents().getCardsIn(ZoneType.Battlefield);
                tgtCards = AbilityUtils.filterListByType(tgtCards, sa.getParam("AllValid"), sa);
                if (tgtCards.isEmpty()) {
                    return new AiAbilityDecision(0, AiPlayDecision.MissingNeededCards);
                }
            }
            final List<String> lose = Lists.newArrayList();

            if (sa.hasParam("LoseControl")) {
                lose.addAll(Lists.newArrayList(sa.getParam("LoseControl").split(",")));
            }

            if (lose.contains("EOT")
                    && game.getPhaseHandler().getPhase().isAfter(PhaseType.COMBAT_DECLARE_ATTACKERS)) {
                return new AiAbilityDecision(0, AiPlayDecision.AnotherTime);
            } else {
                return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
            }
        } else {
            return this.canPlay(ai, sa);
        }
    }

    /**
     * Whether this ability hands a permanent over instead of taking one: it names a new controller,
     * only the AI's own permanents are legal targets, and that new controller is somebody else (or
     * is not settled yet because it comes from a target the parent ability has still to pick).
     */
    private static boolean givesControlAway(final Player ai, final SpellAbility sa) {
        if (!sa.hasParam("NewController")) {
            return false;
        }
        if (!CardLists.getTargetableCards(ai.getOpponents().getCardsIn(ZoneType.Battlefield), sa).isEmpty()
                || CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Battlefield), sa).isEmpty()) {
            return false;
        }
        final List<Player> newController = AbilityUtils.getDefinedPlayers(sa.getHostCard(),
                sa.getParam("NewController"), sa);
        return newController.isEmpty() || !newController.contains(ai);
    }

    /**
     * Picks one of the AI's own permanents to give away. Permanents whose static abilities work
     * against their own controller go first - losing one of those is a gain, not a cost, and it can
     * be the only way out of a lock the AI put itself into. Failing that the AI only parts with
     * something it cannot use anyway, and takes the least valuable of those, so an optional ability
     * is declined rather than paid for with a working permanent.
     */
    private static AiAbilityDecision chooseDonation(final Player ai, final SpellAbility sa, final boolean mandatory) {
        sa.resetTargets();
        final CardCollection targetable = CardLists.getTargetableCards(ai.getCardsIn(ZoneType.Battlefield), sa);
        if (targetable.isEmpty()) {
            return new AiAbilityDecision(0, AiPlayDecision.TargetingFailed);
        }

        // Permanents that restrict their own controller, judged from the static ability layers.
        CardCollection expendable = CardLists.filter(targetable, ComputerUtilCard::restrictsItsController);
        if (expendable.isEmpty()) {
            // Permanents the card scripts flag as good things to hand over.
            final Card flagged = ComputerUtil.getCardPreference(ai, sa.getHostCard(), "DonateMe",
                    CardLists.filter(targetable, CardPredicates.hasSVar("DonateMe")));
            if (flagged != null) {
                expendable = new CardCollection(flagged);
            }
        }
        if (expendable.isEmpty()) {
            // Creatures the AI gets nothing out of.
            expendable = CardLists.filter(targetable, c -> ComputerUtilCard.isUselessCreature(ai, c));
        }

        // Among the permanents worth losing, hand over the one the opponent profits from the least.
        Card choice = expendable.isEmpty() ? null : ComputerUtilCard.getWorstAI(expendable);
        if (choice == null && mandatory) {
            choice = ComputerUtilCard.getWorstAI(targetable);
        }
        if (choice == null) {
            return new AiAbilityDecision(0, AiPlayDecision.CantPlayAi);
        }
        sa.getTargets().add(choice);
        return new AiAbilityDecision(100, AiPlayDecision.WillPlay);
    }

    @Override
    protected Player chooseSinglePlayer(Player ai, SpellAbility sa, Iterable<Player> options, Map<String, Object> params) {
        final List<Card> cards = Lists.newArrayList();
        for (Player p : options) {
            cards.addAll(p.getCreaturesInPlay());
        }
        Card chosen = ComputerUtilCard.getBestCreatureAI(cards);
        return chosen != null ? chosen.getController() : Iterables.getFirst(options, null);
    }

    @Override
    public boolean willPayUnlessCost(Player payer, SpellAbility sa, Cost cost, boolean alreadyPaid, FCollectionView<Player> payers) {
        // Pay to gain Control
        if (sa.hasParam("UnlessSwitched")) {
            final Card host = sa.getHostCard();

            final Card gameCard = host.getGame().getCardState(host, null);
            if (gameCard == null
                    || !gameCard.isInPlay() // not in play
                    || payer.equals(gameCard.getController()) // already in control
                    ) {
                return false;
            }
        }

        return super.willPayUnlessCost(payer, sa, cost, alreadyPaid, payers);
    }
}
