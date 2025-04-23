package org.magiclib.bounty;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignEventListener;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.listeners.FleetEventListener;
import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.fleets.AutoDespawnScript;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * Ends bounties based on battle results.
 *
 * @author Wisp
 */
public final class MagicBountyBattleListener implements FleetEventListener {
    /**
     * Whether this listener is finished listening to battles that this bounty fleet gets into.
     */
    private boolean isDone = false;

    @NotNull
    private final List<String> bountyKeys;
    public void addBountyKeyIfNotExist(@NotNull String bountyKey) {
        if (!bountyKeys.contains(bountyKey)) {
            bountyKeys.add(bountyKey);
        }
    }
    //String bountyKey;

    public MagicBountyBattleListener(@NotNull String bountyKey) {
        this.bountyKeys = new ArrayList<>();
        this.bountyKeys.add(bountyKey);
    }

    @Override
    public void reportFleetDespawnedToListener(CampaignFleetAPI fleet, CampaignEventListener.FleetDespawnReason reason, Object param) {
        if (isDone) {
            return;
        }

        fleet.removeEventListener(this);

        ActiveBounty anyBounty = null;

        //Get any bounty targeting this fleet, they all point to this fleet anyway
        for (String key : bountyKeys) {
            anyBounty = MagicBountyCoordinator.getInstance().getActiveBounty(key);
            if (anyBounty != null) {
                break;
            }
        }

        if (anyBounty == null) return;

        if (fleet.getId().equals(anyBounty.getFleet().getId())) {
            fleet.setCommander(fleet.getFaction().createRandomPerson());

            for (String key : bountyKeys) {
                ActiveBounty bounty = MagicBountyCoordinator.getInstance().getActiveBounty(key);
                if (bounty != null) {
                    bounty.endBounty(new ActiveBounty.BountyResult.EndedWithoutPlayerInvolvement());
                }
            }

            Global.getSector().addScript(new AutoDespawnScript(fleet));
        }
    }

    /**
     * "bountyFleet" will be null if the listener is registered with the ListenerManager, and non-null
     * if the listener is added directly to a fleet.
     * We attach it to the bounty fleet, so `bountyFleet` will always be the bounty fleet.
     */
    @Override
    public void reportBattleOccurred(CampaignFleetAPI bountyFleet, CampaignFleetAPI winningFleet, BattleAPI battle) {
        ActiveBounty anyBounty = null;

        //Get any bounty targeting this fleet, they all point to this fleet anyway
        for (String key : bountyKeys) {
            anyBounty = MagicBountyCoordinator.getInstance().getActiveBounty(key);
            if (anyBounty != null) {
                break;
            }
        }

        CampaignFleetAPI playerFleet = Global.getSector().getPlayerFleet();

        if (anyBounty == null) return;

        /////////// Below is copied (and heavily modified) from PersonBountyIntel.reportBattleOccurred.
        if (isDone) return;

        boolean playerInvolved = battle.isPlayerInvolved();

        if (bountyFleet.getId().equals(anyBounty.getFleet().getId())) {
            PersonAPI bountyCommander = anyBounty.getCaptain();

            if (battle.isInvolved(bountyFleet) && !playerInvolved) {
                if (bountyFleet.getFlagship() == null || bountyFleet.getFlagship().getCaptain() != bountyCommander) {
                    bountyFleet.setCommander(bountyFleet.getFaction().createRandomPerson());
                    //Global.getSector().reportEventStage(this, "other_end", market.getPrimaryEntity(), messagePriority);
                    for (String key : bountyKeys) {
                        ActiveBounty bounty = MagicBountyCoordinator.getInstance().getActiveBounty(key);
                        if (bounty != null) {
                            bounty.endBounty(new ActiveBounty.BountyResult.EndedWithoutPlayerInvolvement());
                        }
                    }
                    // Quietly despawn the fleet when player goes away, since they can't complete the bounty.
                    Global.getSector().addScript(new AutoDespawnScript(bountyFleet));
//                        result = new PersonBountyIntel.BountyResult(PersonBountyIntel.BountyResultType.END_OTHER, 0, null);
//                        sendUpdateIfPlayerHasIntel(result, true);
//                        cleanUpFleetAndEndIfNecessary();
                    return;
                }
            }

            if (!playerInvolved || !battle.isInvolved(bountyFleet) || battle.onPlayerSide(bountyFleet)) {
                return;
            }

            boolean didDisableOrDestroyOriginalFlagship = bountyFleet.getFlagship() == null || bountyFleet.getFlagship().getCaptain() != bountyCommander;
            boolean didPlayerSalvageFlagship = false;
            List<FleetMemberAPI> bountyFleetBeforeBattle = bountyFleet.getFleetData().getSnapshot();

            if (anyBounty.getFlagshipId() != null) {
                for (FleetMemberAPI fleetMember : playerFleet.getFleetData().getMembersListCopy()) {

                    for (FleetMemberAPI ship : bountyFleetBeforeBattle) {
                        // Look for the flagship of the bounty fleet's presence in the player fleet.
                        if (fleetMember.getId().equals(anyBounty.getFlagshipId()) && fleetMember.getId().equals(ship.getId())) {
                            Global.getLogger(MagicBountyBattleListener.class).info(String.format("Player salvaged flagship %s (%s)", ship.getShipName(), ship.getId()));
                            didPlayerSalvageFlagship = true;
                        }
                    }
                }
            }

            for (String key : bountyKeys) {
                ActiveBounty bounty = MagicBountyCoordinator.getInstance().getActiveBounty(key);
                if(bounty == null) continue;

                switch (bounty.getSpec().job_type) {
                    case Assassination:
                        if (didDisableOrDestroyOriginalFlagship) {
                            bounty.endBounty(new ActiveBounty.BountyResult.Succeeded(true));
                            isDone = true;
                        }

                        break;
                    case Destruction:
                        if (didDisableOrDestroyOriginalFlagship)
                            if (!didPlayerSalvageFlagship) {
                                bounty.endBounty(new ActiveBounty.BountyResult.Succeeded(true));
                                isDone = true;
                            } else {
                                // If the bounty required destroying the target, but player salvaged their ship, they don't get credits.
                                bounty.endBounty(new ActiveBounty.BountyResult.FailedSalvagedFlagship());
                                isDone = true;
                            }

                        break;
                    case Obliteration:
                        if (bountyFleet.getFleetSizeCount() <= 0) {
                            bounty.endBounty(new ActiveBounty.BountyResult.Succeeded(true));
                            isDone = true;
                        }

                        break;
                    case Neutralization:
                    case Neutralisation:
                        float fpPostFight = bountyFleet.getFleetPoints();

                        if ((fpPostFight / bounty.getInitialBountyFleetPoints()) <= (1f / 3f)) {
                            bounty.endBounty(new ActiveBounty.BountyResult.Succeeded(true));
                            isDone = true;
                        }

                        break;
                }
            }

        }
    }
}
