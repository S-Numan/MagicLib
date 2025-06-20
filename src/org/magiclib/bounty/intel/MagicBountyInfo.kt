package org.magiclib.bounty.intel

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.LocationAPI
import com.fs.starfarer.api.campaign.SectorEntityToken
import com.fs.starfarer.api.campaign.StarSystemAPI
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin
import com.fs.starfarer.api.fleet.FleetMemberAPI
import com.fs.starfarer.api.impl.campaign.ids.Factions
import com.fs.starfarer.api.impl.campaign.procgen.Constellation
import com.fs.starfarer.api.impl.campaign.rulecmd.salvage.special.BreadcrumbSpecial
import com.fs.starfarer.api.ui.*
import com.fs.starfarer.api.util.Misc
import org.lwjgl.util.vector.Vector2f
import org.magiclib.bounty.ActiveBounty
import org.magiclib.bounty.MagicBountyCoordinator
import org.magiclib.bounty.MagicBountyLoader.*
import org.magiclib.bounty.MagicBountySpec
import org.magiclib.bounty.MagicBountyUtilsInternal
import org.magiclib.bounty.ui.InteractiveUIPanelPlugin
import org.magiclib.kotlin.setAlpha
import org.magiclib.kotlin.ucFirst
import org.magiclib.util.MagicCampaign
import org.magiclib.util.MagicTxt
import java.awt.Color
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.text.isEmpty

open class MagicBountyInfo(val bountyKey: String, val bountySpec: MagicBountySpec) : BountyInfo {
    val activeBounty: ActiveBounty?
        get() = MagicBountyCoordinator.getInstance().getActiveBounty(bountyKey)
    var holdingPanel: CustomPanelAPI? = null
    var panelThatCanBeRemoved: CustomPanelAPI? = null

    override fun getBountyId(): String {
        return bountyKey
    }

    override fun getBountyName(): String {
        return bountySpec.job_name
    }

    override fun getBountyType(): String {
        return bountySpec.job_type.name
    }

    override fun getBountyPayout(): Int {
        return activeBounty?.calculateCreditReward()?.toInt() ?: bountySpec.job_credit_reward
    }

    override fun getJobIcon(): String {
        if (bountySpec.job_show_captain) {
            return activeBounty?.fleet?.commander?.portraitSprite ?: bountySpec.target_portrait
            ?: "graphics/portraits/portrait_generic_grayscale.png"
        }
        return "graphics/portraits/portrait_generic_grayscale.png"
    }

    override fun getLocationIfBountyIsActive(): LocationAPI? {
        return activeBounty?.fleetSpawnLocation?.containingLocation
    }

    override fun getSortIndex(): Int {
        return when (activeBounty?.stage) {
            ActiveBounty.Stage.Accepted -> 0
            ActiveBounty.Stage.NotAccepted -> 1
            ActiveBounty.Stage.Succeeded -> 3
            ActiveBounty.Stage.ExpiredAfterAccepting -> 4
            ActiveBounty.Stage.ExpiredWithoutAccepting -> 4
            ActiveBounty.Stage.FailedSalvagedFlagship -> 4
            ActiveBounty.Stage.EndedWithoutPlayerInvolvement -> 4
            ActiveBounty.Stage.Dismissed -> 4
            else -> 1
        }
    }

    override fun notifyWhenAvailable(): Boolean {
        return true
    }

    override fun notifiedUserThatBountyIsAvailable() {
        activeBounty ?: MagicBountyCoordinator.getInstance().createActiveBounty(bountyKey, bountySpec)
    }

    override fun addNotificationBulletpoints(info: TooltipMakerAPI) {
        activeBounty?.givingFaction?.let {
            info.addPara(
                MagicTxt.getString("mb_intel_offeredBy").format(it.displayName),
                2f,
                activeBounty!!.givingFactionTextColor,
                it.displayName
            )
        }

        val creditReward = getBountyPayout().toFloat()
        if (creditReward > 0f) {
            val rewardText = Misc.getDGSCredits(creditReward)
            info.addPara(MagicTxt.getString("mb_credits").format(rewardText), 1f, Misc.getHighlightColor(), rewardText)
        }
    }

    override fun shouldAlwaysShow(): Boolean {
        return activeBounty?.stage == ActiveBounty.Stage.Accepted
    }

    /**
     * Whether the bounty should be shown as available to the player.
     * If it should be, creates it if it doesn't exist.
     */
    override fun shouldShow(): Boolean {
        if (activeBounty != null) {
            when (activeBounty!!.stage) {
                ActiveBounty.Stage.Accepted -> return true
                ActiveBounty.Stage.Succeeded -> return false
                ActiveBounty.Stage.ExpiredAfterAccepting -> return false
                ActiveBounty.Stage.ExpiredWithoutAccepting -> return false
                ActiveBounty.Stage.FailedSalvagedFlagship -> return false
                ActiveBounty.Stage.EndedWithoutPlayerInvolvement -> return false
                ActiveBounty.Stage.Dismissed -> return false
                else -> { /* do nothing */
                } //NotAccepted
            }
        }

        if (bountyKey in MagicBountyCoordinator.getInstance().completedBounties) {
            return false
        }

        if (!MagicCampaign.isAvailableToPlayer(
                bountySpec.trigger_player_minLevel,
                bountySpec.trigger_min_days_elapsed,
                bountySpec.trigger_min_fleet_size,
                bountySpec.trigger_memKeys_all,
                bountySpec.trigger_memKeys_any,
                bountySpec.trigger_memKeys_none,
                bountySpec.trigger_playerRelationship_atLeast,
                bountySpec.trigger_playerRelationship_atMost
            )
        ) {
            return false
        }

        //CHECK FOR EXISTING FLEET
        if (bountySpec.existing_target_memkey != null) { // Bounty has pre-existing target
            var targetFleetFound = false
            for (s in Global.getSector().starSystems) {
                for (f in s.fleets) {
                    if (f.memoryWithoutUpdate.contains(bountySpec.existing_target_memkey)) {
                        targetFleetFound = true
                        break
                    }
                }
                if (targetFleetFound) break
            }
            if (!targetFleetFound) { // If the bounty was intended to target an existing fleet, and no fleet was found
                return false // Don't show it, as the target fleet does not exist
            }
        }

        //check if close enough to receive from an offering faction
        val rangeToShowBounties = 10f
        var withinRange = false
        for (system in Misc.getNearbyStarSystems(Global.getSector().playerFleet, rangeToShowBounties)) {
            for (market in Misc.getMarketsInLocation(system)) {
                if (MagicCampaign.isAvailableAtMarket(
                        market,
                        bountySpec.trigger_market_id,
                        bountySpec.trigger_marketFaction_any,
                        bountySpec.trigger_marketFaction_alliedWith,
                        bountySpec.trigger_marketFaction_none,
                        bountySpec.trigger_marketFaction_enemyWith,
                        bountySpec.trigger_market_minSize
                    )
                ) {
                    withinRange = true
                    break
                }
            }
        }
        if (!withinRange) return false

        if (activeBounty == null) {
            MagicBountyCoordinator.getInstance().createActiveBounty(bountyKey, bountySpec)
        }

        return true
    }

    override fun decorateListItem(
        plugin: BountyListPanelPlugin.BountyItemPanelPlugin,
        tooltip: TooltipMakerAPI,
        width: Float,
        height: Float
    ) {
        var jobIcon = this.getJobIcon()
        // Double check portrait sprite. If it fails, use a generic one and log it.
        kotlin.runCatching { Global.getSettings().loadTexture(jobIcon) }
            .onFailure {
                Global.getLogger(this::class.java).error("Failed to load bounty icon: $jobIcon", it)
                jobIcon = "graphics/portraits/portrait_generic_grayscale.png"
            }
        val textTooltip = tooltip.beginImageWithText(
            jobIcon,
            64f,
            width,
            true
        )
        textTooltip.addPara(this.getBountyName(), 0f)

        if (bountySpec.job_show_type) {
            textTooltip.addPara(this.getBountyType(), Misc.getGrayColor(), 0f)
        }

        textTooltip.addPara(
            "Base reward: ${Misc.getDGSCredits(this.getBountyPayout().toFloat())}",
            Misc.getHighlightColor(),
            0f
        )
        tooltip.addImageWithText(2f)

        val activeBountyLocal: ActiveBounty = activeBounty
            ?: MagicBountyCoordinator.getInstance().createActiveBounty(bountyKey, bountySpec)
            ?: return

        if (activeBountyLocal.stage == ActiveBounty.Stage.Accepted) {
            plugin.baseBgColor = Misc.getDarkHighlightColor().setAlpha(45)
            plugin.hoveredColor = Misc.getDarkHighlightColor().setAlpha(75)
            plugin.selectedColor = Misc.getDarkHighlightColor().setAlpha(125)
        }
    }

    override fun layoutPanel(tooltip: TooltipMakerAPI, width: Float, height: Float) {
        if (panelThatCanBeRemoved != null) {
            holdingPanel!!.removeComponent(panelThatCanBeRemoved)
        }

        if (holdingPanel == null) {
            holdingPanel = Global.getSettings().createCustom(width / 2, height, null)
        }

        tooltip.addCustom(holdingPanel, 0f)
        panelThatCanBeRemoved = holdingPanel!!.createCustomPanel(width / 2, height, null)

        val width = width - 16f
        val height = height - 16f
        val activeBountyLocal: ActiveBounty = activeBounty
            ?: MagicBountyCoordinator.getInstance().createActiveBounty(bountyKey, bountySpec)
            ?: return

        val leftPanel = panelThatCanBeRemoved!!.createCustomPanel(width / 2, height, null)
        showBountyText(leftPanel, width / 2, height)
        panelThatCanBeRemoved!!.addComponent(leftPanel)

        val rightPanelPlugin = InteractiveUIPanelPlugin()
        val rightPanelWidth = width / 2 - 16f
        val rightPanel = panelThatCanBeRemoved!!.createCustomPanel(rightPanelWidth, height - 4f, rightPanelPlugin)

        val rightScrollPanel = rightPanel.createUIElement(rightPanelWidth, height - 36f, true)
        val rightChildPanel = rightPanel.createCustomPanel(rightPanelWidth, height - 36f, null)
        showTargetInfo(rightChildPanel, rightPanelWidth, height - 36f)
        rightScrollPanel.addCustom(rightChildPanel, 0f)
        rightPanel.addUIElement(rightScrollPanel).inTL(0f, 0f)

        val actionTooltip = rightPanel.createUIElement(rightPanelWidth, 32f, false)

        if (activeBountyLocal.stage == ActiveBounty.Stage.NotAccepted) {
            val acceptButton = actionTooltip.addButton(bountySpec.job_pick_option, null, rightPanelWidth, 24f, 0f)
            rightPanelPlugin.addButton(acceptButton) {
                acceptButton.isChecked = false
                activeBountyLocal.let {
                    it.acceptBounty(
                        Global.getSector().playerFleet,
                        it.calculateCreditReward(),
                        it.spec.job_reputation_reward,
                        it.spec.job_forFaction
                    )
                }
                BountyBoardIntelPlugin.refreshPanel(this)
            }
        } else if (activeBountyLocal.stage == ActiveBounty.Stage.Accepted) {

            //Add plot course button if there is a clear destination
            val dis = activeBountyLocal.spec.job_show_distance
            if(dis != ShowDistance.Distance && dis != ShowDistance.None// && dis != ShowDistance.Vague
                ) {

                var location: SectorEntityToken? = null
                if(dis == ShowDistance.Exact || dis == ShowDistance.System) {
                    location = Misc.getDistressJumpPoint(activeBountyLocal.fleet.containingLocation as StarSystemAPI)
                } else {
                    val constellation = activeBountyLocal.fleet.constellation
                    if(constellation != null)
                        location = createConstellationCenterToken(constellation)
                }

                if(location != null) {
                    val courseButton =
                        actionTooltip.addButton(MagicTxt.getString("mb_plot_course"), null, rightPanelWidth, 24f, 0f)
                    rightPanelPlugin.addButton(courseButton) {
                        courseButton.isChecked = false
                        Global.getSector().layInCourseFor(
                            location
                        )
                    }
                }
            }
        }

        rightPanel.addUIElement(actionTooltip).inBL(0f, 0f)
        panelThatCanBeRemoved!!.addComponent(rightPanel).rightOfTop(leftPanel, 2f)

        holdingPanel!!.addComponent(panelThatCanBeRemoved)
    }

    open fun showBountyText(panel: CustomPanelAPI, width: Float, height: Float): TooltipMakerAPI {
        val textTooltip = panel.createUIElement(width, height, true)
        val bountyFactionId = "ML_bounty"

        if (activeBounty?.stage == ActiveBounty.Stage.Accepted) {
            textTooltip.addPara(MagicTxt.getString("mb_descAccepted"), Misc.getHighlightColor(), 0f)
            textTooltip.addSpacer(12f)
        }

        bountySpec.job_description?.split("\n")
            ?.map {
                MagicTxt.MagicDisplayableText(MagicBountyUtilsInternal.replaceStringVariables(activeBounty, it))
            }
            ?.forEach { bountyText ->
                textTooltip.addPara(bountyText.format, 3f, Misc.getHighlightColor(), *bountyText.highlights)
            }
        textTooltip.addSpacer(10f)

        if (activeBounty?.spec?.job_show_type == true) {
            val bounty = activeBounty!!

            when (activeBounty?.spec?.job_type) {
                JobType.Assassination -> if (bounty.targetFaction == null || bounty.targetFaction?.id == bountyFactionId) {
                    textTooltip.addPara(
                        MagicTxt.getString("mb_intelType"),
                        4f,
                        Misc.getTextColor(),
                        Misc.getHighlightColor(),
                        MagicTxt.getString("mb_type_assassination1")
                    )
                } else {
                    val label: LabelAPI = textTooltip.addPara(
                        MagicTxt.getString("mb_intelType0") +
                                MagicTxt.getString("mb_type_assassination1") +
                                MagicTxt.getString("mb_intelType1") +
                                bounty.targetFaction?.displayName +
                                MagicTxt.getString("mb_intelType2"),
                        4f
                    )
                    label.setHighlight(
                        MagicTxt.getString("mb_type_assassination1"),
                        bounty.targetFaction?.displayName
                    )
                    label.setHighlightColors(Misc.getHighlightColor(), bounty.targetFactionTextColor)
                }

                JobType.Destruction -> if (bounty.targetFaction == null || bounty.targetFaction?.id == bountyFactionId) {
                    textTooltip.addPara(
                        MagicTxt.getString("mb_intelType"),
                        4f,
                        Misc.getTextColor(),
                        Misc.getHighlightColor(),
                        MagicTxt.getString("mb_type_destruction1")
                    )
                } else {
                    val label: LabelAPI = textTooltip.addPara(
                        (MagicTxt.getString("mb_intelType0") +
                                MagicTxt.getString("mb_type_destruction1") +
                                MagicTxt.getString("mb_intelType1") +
                                bounty.targetFaction?.displayName +
                                MagicTxt.getString("mb_intelType2")),
                        4f
                    )
                    label.setHighlight(
                        MagicTxt.getString("mb_type_destruction1"),
                        bounty.targetFaction?.displayName
                    )
                    label.setHighlightColors(Misc.getHighlightColor(), bounty.targetFactionTextColor)
                }

                JobType.Obliteration -> if (bounty.targetFaction == null || bounty.targetFaction?.id == bountyFactionId) {
                    textTooltip.addPara(
                        MagicTxt.getString("mb_intelType"),
                        4f,
                        Misc.getTextColor(),
                        Misc.getHighlightColor(),
                        MagicTxt.getString("mb_type_obliteration1")
                    )
                } else {
                    val label: LabelAPI = textTooltip.addPara(
                        (MagicTxt.getString("mb_intelType0") +
                                MagicTxt.getString("mb_type_obliteration1") +
                                MagicTxt.getString("mb_intelType1") +
                                bounty.targetFaction?.displayName +
                                MagicTxt.getString("mb_intelType2")),
                        4f
                    )
                    label.setHighlight(
                        MagicTxt.getString("mb_type_obliteration1"),
                        bounty.targetFaction?.displayName
                    )
                    label.setHighlightColors(Misc.getHighlightColor(), bounty.targetFactionTextColor)
                }

                JobType.Neutralization,
                JobType.Neutralisation -> if (bounty.targetFaction == null || bounty.targetFaction?.id == bountyFactionId) {
                    textTooltip.addPara(
                        MagicTxt.getString("mb_intelType"),
                        4f,
                        Misc.getTextColor(),
                        Misc.getHighlightColor(),
                        MagicTxt.getString("mb_type_neutralisation1")
                    )
                } else {
                    val label: LabelAPI = textTooltip.addPara(
                        (MagicTxt.getString("mb_intelType0") +
                                MagicTxt.getString("mb_type_neutralisation1") +
                                MagicTxt.getString("mb_intelType1") +
                                bounty.targetFaction?.displayName +
                                MagicTxt.getString("mb_intelType2")),
                        4f
                    )
                    label.setHighlight(
                        MagicTxt.getString("mb_type_neutralisation1"),
                        bounty.targetFaction?.displayName
                    )
                    label.setHighlightColors(Misc.getHighlightColor(), bounty.targetFactionTextColor)
                }

                else -> {}
            }
        }

        val reward = activeBounty?.rewardCredits ?: activeBounty?.calculateCreditReward()
        val givingFaction = activeBounty?.givingFaction
        if (reward != null && givingFaction != null) {
            val rewardText = Misc.getDGSCredits(reward)
            textTooltip.addPara(
                MagicTxt.getString("mb_descFactionReward")
                    .format(givingFaction.displayNameWithArticle, givingFaction.displayNameIsOrAre, rewardText),
                2f,
                arrayOf(givingFaction.color, Misc.getHighlightColor()),
                givingFaction.displayNameWithArticle, rewardText
            )
        } else if (givingFaction != null) {
            textTooltip.addPara(
                MagicTxt.getString("mb_descFactionNoReward")
                    .format(givingFaction.displayNameWithArticle, givingFaction.displayNameIsOrAre),
                2f,
                givingFaction.color,
                givingFaction.displayNameWithArticle
            )
        } else if (reward != null) {
            val rewardText = Misc.getDGSCredits(reward)
            textTooltip.addPara(
                MagicTxt.getString("mb_descAnonymousReward").format(rewardText),
                2f,
                Misc.getHighlightColor(),
                rewardText
            )
        }

        panel.addUIElement(textTooltip)

        return textTooltip
    }

    open fun showTargetInfo(panel: CustomPanelAPI, width: Float, height: Float): TooltipMakerAPI {
        val targetInfoTooltip = panel.createUIElement(width, height, true)
        val childPanelWidth = width - 16f

        val bounty = activeBounty!!

        if (bountySpec.job_type == JobType.Assassination && bountySpec.job_show_captain) {
            val portrait = targetInfoTooltip.beginImageWithText(getJobIcon(), 64f)
            var displayName = bounty.fleet.commander.nameString
            val targetFirstName = bounty.captain.name.first
            val targetLastName = bounty.captain.name.last
            if (targetFirstName != null || targetLastName != null) {
                displayName = "$targetFirstName $targetLastName"
                if (targetFirstName == null || targetFirstName.isEmpty())
                    displayName = targetLastName
                else if (targetLastName == null || targetLastName.isEmpty())
                    displayName = targetFirstName
            }
            portrait.addPara(displayName, bounty.targetFactionTextColor, 0f)
            portrait.addPara(bounty.fleet.commander.rank.ucFirst(), 2f)
            targetInfoTooltip.addImageWithText(0f)
        }

        val location = getLocationIfBountyIsActive()
        if (location is StarSystemAPI) {

            val params = MapParams()
            val w = targetInfoTooltip.widthSoFar
            val h = (w / 1.6f).roundToInt().toFloat()

            if (bountySpec.job_show_distance != ShowDistance.None) {
                when (bountySpec.job_show_distance) {
                    ShowDistance.Exact -> {
                        params.showSystem(location)
                        params.arrows.add(IntelInfoPlugin.ArrowData(Global.getSector().playerFleet, location.center))

                        targetInfoTooltip.addPara(
                            createLocationPreciseText(bounty),
                            10f,
                            location.lightColor,
                            bounty.fleetSpawnLocation.starSystem.nameWithLowercaseType
                        )
                    }

                    ShowDistance.System -> {
                        params.showSystem(location)
                        params.arrows.add(IntelInfoPlugin.ArrowData(Global.getSector().playerFleet, location.center))

                        targetInfoTooltip.addPara(
                            MagicTxt.getString("mb_distance_system"),
                            10f,
                            arrayOf(Misc.getTextColor(), location.lightColor),
                            MagicTxt.getString("mb_distance_they"),
                            bounty.fleetSpawnLocation.starSystem.nameWithLowercaseType
                        )
                    }

                    /*ShowDistance.Vague -> {
                        params.filterData.names = false

                        val distance = bounty.fleetSpawnLocation.containingLocation.location.length()
                        var vague = MagicTxt.getString("mb_distance_core")
                        if (distance > MagicVariables.getSectorSize() * 0.6f) {
                            vague = MagicTxt.getString("mb_distance_far")
                        } else if (distance > MagicVariables.getSectorSize() * 0.33f) {
                            vague = MagicTxt.getString("mb_distance_close")
                        }
                        targetInfoTooltip.addPara(
                            MagicTxt.getString("mb_distance_vague"),
                            10f,
                            Misc.getTextColor(),
                            Misc.getHighlightColor(),
                            vague
                        )
                    }*///Commented out due to seeming to be a bad mechanic with the current implementation of the bounty board. Given pre-existing use of it in some mods (such as Seeker), enabling this on an update may cause issues for some users.

                    ShowDistance.Distance -> {
                        params.filterData.names = false

                        targetInfoTooltip.addPara(MagicTxt.getString("mb_distance"),
                            10f,
                            Misc.getTextColor(),
                            Misc.getHighlightColor(),
                            Misc.getDistanceLY(Global.getSector().playerFleet, bounty.fleetSpawnLocation).roundToInt().toString());
                    }

                    else -> {
                        val constellation = location.constellation
                        if(constellation != null) {
                            //Show constellation
                            params.filterData.constellations = true
                            params.filterData.names = false
                            params.showConsellations = setOf(constellation)
                            params.smallConstellations = true

                            //Point towards center of constellation
                            val token = createConstellationCenterToken(constellation)
                            if (token != null) {
                                params.arrows.add(IntelInfoPlugin.ArrowData(Global.getSector().playerFleet, token))

                                params.markers = listOf(MarkerData(token.location, Global.getSector().hyperspace))
                            }

                        } else {
                            params.showSystem(location)
                            params.arrows.add(IntelInfoPlugin.ArrowData(Global.getSector().playerFleet, location.center))
                        }

                        targetInfoTooltip.addPara(
                            createLocationEstimateText(bounty),
                            10f,
                            location.lightColor,
                            BreadcrumbSpecial.getLocationDescription(bounty.fleetSpawnLocation, false)
                        )
                    }
                }
            }

            params.positionToShowAllMarkersAndSystems(false, w.coerceAtMost(h))
            params.markers = null//Markers are purely for positioning the camera. Don't render them.

            params.filterData.fuel = true

            val map = targetInfoTooltip.createSectorMap(childPanelWidth, 200f, params, null)
            targetInfoTooltip.addCustom(map, 2f)

        } else {
            targetInfoTooltip.setButtonFontOrbitron20Bold()
            targetInfoTooltip.addPara(MagicTxt.getString("mb_descLocationUnknown"), 3f, Color.RED).position.inTMid(2f)
        }

        activeBounty?.let {
            showFleet(targetInfoTooltip, childPanelWidth, it)

            if (it.spec.job_show_captain) {
                targetInfoTooltip.addPara(MagicTxt.getString("mb_hvb_skillsHeader"), 8f)
                targetInfoTooltip.addSkillPanel(it.captain, 2f)
            }
        }

        panel.addUIElement(targetInfoTooltip)

        return targetInfoTooltip
    }

    fun acceptBounty() {
        activeBounty?.let {
            it.acceptBounty(
                Global.getSector().playerFleet,
                it.calculateCreditReward(),
                it.spec.job_reputation_reward,
                it.spec.job_forFaction
            )
        }
        BountyBoardIntelPlugin.refreshPanel(this)
    }

    open fun showFleet(
        info: TooltipMakerAPI,
        width: Float,
        activeBounty: ActiveBounty
    ) {
        val setting = activeBounty.spec.job_show_fleet
        val ships = activeBounty.fleet.fleetData.membersListCopy

        val flagship = mutableListOf<FleetMemberAPI>()
        activeBounty.spec.fleet_flagship_name?.let { flagshipName ->
            ships.filter { it.shipName == flagshipName }.forEach { flagship.add(it) }
        }
        if (flagship.isEmpty() && activeBounty.fleet.flagship != null) {
            flagship.add(activeBounty.fleet.flagship)
        }

        val preset = mutableListOf<FleetMemberAPI>()
        if (activeBounty.spec.fleet_preset_ships != null && activeBounty.spec.fleet_preset_ships.isNotEmpty()) {
            val specPresetShips = activeBounty.spec.fleet_preset_ships!!
            preset.addAll(flagship)
            ships.filter { specPresetShips.contains(it.variant.hullVariantId) }.forEach { preset.add(it) }
        }

        val factionBaseUIColor = activeBounty.targetFaction?.baseUIColor ?: Global.getSector().getFaction(
            Factions.PIRATES
        ).baseUIColor
        val columns = 10
        when (setting) {
            ShowFleet.Text -> {
                //write the number of ships
                var num = ships.size
                if (num < 5) {
                    num = 5
                    info.addPara(
                        MagicTxt.getString("mb_fleet6"),
                        2f,
                        Misc.getTextColor(),
                        Misc.getHighlightColor(),
                        "" + num
                    )
                    return
                } else if (num < 10) num = 5 else if (num < 20) num = 10 else if (num < 30) num = 20 else num = 30
                info.addPara(
                    MagicTxt.getString("mb_fleet5"),
                    2f,
                    Misc.getTextColor(),
                    Misc.getHighlightColor(),
                    "" + num
                )
                //show the flagship
                info.addPara(MagicTxt.getString("mb_fleet0") + MagicTxt.getString("mb_fleet"), 2f)
                info.addShipList(
                    columns,
                    1,
                    (width - 10) / columns,
                    factionBaseUIColor,
                    flagship,
                    10f
                )
            }

            ShowFleet.Flagship -> {
                info.addPara(MagicTxt.getString("mb_fleet0") + MagicTxt.getString("mb_fleet"), 2f)
                info.addShipList(
                    columns,
                    1,
                    (width - 10) / columns,
                    factionBaseUIColor,
                    flagship,
                    10f
                )
            }

            ShowFleet.FlagshipText -> {
                //show the flagship
                info.addPara(MagicTxt.getString("mb_fleet0") + MagicTxt.getString("mb_fleet"), 2f)
                info.addShipList(
                    columns,
                    1,
                    (width - 10) / columns,
                    factionBaseUIColor,
                    flagship,
                    10f
                )

                //write the number of other ships
                var num = ships.size - 1
                num = (num.toFloat() * (1f + Misc.random.nextFloat() * 0.5f)).roundToInt()
                if (num < 5) {
                    info.addPara(
                        MagicTxt.getString("mb_fleet4"),
                        2f,
                        Misc.getTextColor(),
                        Misc.getHighlightColor()
                    )
                    return
                } else if (num < 10) num = 5 else if (num < 20) num = 10 else if (num < 30) num = 20 else num = 30
                info.addPara(
                    MagicTxt.getString("mb_fleet3"),
                    2f,
                    Misc.getTextColor(),
                    Misc.getHighlightColor(),
                    "" + num
                )
            }

            ShowFleet.Preset -> {
                //show the preset fleet
                info.addPara(MagicTxt.getString("mb_fleet1") + MagicTxt.getString("mb_fleet"), 2f)
                info.addShipList(
                    columns, ceil(preset.size.toDouble() / columns).roundToInt(),
                    (width - 10) / columns,
                    factionBaseUIColor,
                    preset,
                    10f
                )
            }

            ShowFleet.PresetText -> {
                //show the preset fleet
                info.addPara(MagicTxt.getString("mb_fleet1") + MagicTxt.getString("mb_fleet"), 2f)
                info.addShipList(
                    columns, ceil(preset.size.toDouble() / columns).roundToInt(),
                    (width - 10) / columns,
                    factionBaseUIColor,
                    preset,
                    10f
                )

                //write the number of other ships
                var num = ships.size - preset.size
                num = (num.toFloat() * (1f + Misc.random.nextFloat() * 0.5f)).roundToInt()
                if (num < 5) {
                    info.addPara(
                        MagicTxt.getString("mb_fleet4"),
                        2f,
                        Misc.getTextColor(),
                        Misc.getHighlightColor()
                    )
                    return
                } else if (num < 10) num = 5 else if (num < 20) num = 10 else if (num < 30) num = 20 else num = 30
                info.addPara(
                    MagicTxt.getString("mb_fleet3"),
                    2f,
                    Misc.getTextColor(),
                    Misc.getHighlightColor(),
                    "" + num
                )
            }

            ShowFleet.Vanilla -> {
                //show the Flagship and the 6 biggest ships in the fleet
                info.addPara(MagicTxt.getString("mb_fleet1") + MagicTxt.getString("mb_fleet"), 2f)

                val toShow = ArrayList<FleetMemberAPI>()
                //there are less than 7 ships total, all will be shown
                if (ships.size <= columns) {
                    //add flagship first
                    for (m in ships) {
                        if (m.isFlagship) {
                            toShow.add(m)
                            break
                        }
                    }
                    //then all the rest
                    for (m in ships) {
                        if (!m.isFlagship) {
                            toShow.add(m)
                        }
                    }
                    //display the ships
                    info.addShipList(
                        columns,
                        1,
                        (width - 10) / columns,
                        factionBaseUIColor,
                        toShow,
                        10f
                    )
                    info.addPara(
                        MagicTxt.getString("mb_fleet4"),
                        2f,
                        Misc.getTextColor(),
                        Misc.getHighlightColor()
                    )
                    return
                }
                //If there are more than 7 ships, pick the largest 7
                //add flagship first
                for (m in ships) {
                    if (m.isFlagship) {
                        toShow.add(m)
                        break
                    }
                }
                //then complete the list
                for (m in ships) {
                    if (toShow.size >= columns) break
                    if (!m.isFlagship) toShow.add(m)
                }
                //make the ship list
                info.addShipList(
                    columns,
                    1,
                    (width - 10) / columns,
                    factionBaseUIColor,
                    toShow,
                    10f
                )

                //write the number of other ships
                var num = ships.size - columns
                num = (num.toFloat() * (1f + Misc.random.nextFloat() * 0.5f)).roundToInt()
                if (num < 5) {
                    info.addPara(
                        MagicTxt.getString("mb_fleet4"),
                        2f,
                        Misc.getTextColor(),
                        Misc.getHighlightColor()
                    )
                    return
                } else if (num < 10) num = 5 else if (num < 20) num = 10 else if (num < 30) num = 20 else num = 30
                info.addPara(
                    MagicTxt.getString("mb_fleet3"),
                    2f,
                    Misc.getTextColor(),
                    Misc.getHighlightColor(),
                    "" + num
                )
            }

            ShowFleet.All -> {
                //show the full fleet
                info.addPara(MagicTxt.getString("mb_fleet2") + MagicTxt.getString("mb_fleet"), 2f)
                val toShow = ArrayList<FleetMemberAPI>()
                //add flagship first
                for (m in ships) {
                    if (m.isFlagship) {
                        toShow.add(m)
                        break
                    }
                }
                //then all the rest
                for (m in ships) {
                    if (!m.isFlagship) {
                        toShow.add(m)
                    }
                }
                //display the ships
                info.addShipList(
                    columns, ceil(ships.size.toDouble() / columns).roundToInt(),
                    (width - 10) / columns,
                    factionBaseUIColor,
                    toShow,
                    10f
                )
            }

            else -> {}
        }
    }
}

fun ActiveBounty.calculateCreditReward(): Float? {
    return this.calculateCreditReward(
        MagicBountyCoordinator.getInstance().preScalingCreditRewardMultiplier,
        MagicBountyCoordinator.getInstance().postScalingCreditRewardMultiplier
    )
}

fun getConstellationSystemsCenter(constellation: Constellation): Vector2f? {
    val systems = constellation.systems
    if (systems.isEmpty()) return null

    val total = Vector2f(0f, 0f)
    for (system in systems) {
        Vector2f.add(total, system.location, total)
    }

    total.scale(1f / systems.size)
    return total
}

fun createConstellationCenterToken(constellation: Constellation): SectorEntityToken? {
    val center = getConstellationSystemsCenter(constellation) ?: return null
    val hyperspace = Global.getSector().hyperspace

    // Create a temporary token at the calculated position
    return hyperspace.createToken(center.x, center.y)
}
