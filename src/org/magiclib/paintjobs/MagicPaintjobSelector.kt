package org.magiclib.paintjobs

import com.fs.starfarer.api.Global
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin
import com.fs.starfarer.api.combat.ShipAPI
import com.fs.starfarer.api.combat.ShipVariantAPI
import com.fs.starfarer.api.input.InputEventAPI
import com.fs.starfarer.api.ui.CustomPanelAPI
import com.fs.starfarer.api.ui.UIPanelAPI
import com.fs.starfarer.api.util.FaderUtil
import com.fs.starfarer.api.util.Misc
import com.fs.starfarer.loading.specs.HullVariantSpec
import org.lwjgl.opengl.GL11
import org.magiclib.ReflectionUtils
import org.magiclib.internalextensions.*
import org.magiclib.kotlin.*
import org.magiclib.util.MagicTxt
import java.awt.Color
import kotlin.math.max

/**
 * @author Starficz
 */
internal object MagicPaintjobSelector {
    internal class MagicPaintjobSelectorPlugin(val paintjobSpec: MagicPaintjobSpec?) : BaseCustomUIPanelPlugin() {
        lateinit var selectorPanel: CustomPanelAPI

        val defaultBGColor: Color = Color.BLACK
        val clickedBGColor: Color = Misc.getDarkPlayerColor()
        val hoverBGColor: Color = Misc.getDarkPlayerColor().darker()
        val highlightBGColor: Color = Misc.getDarkPlayerColor().darker().darker()
        val lockedColor: Color = Color.BLACK.setAlpha(170)
        val lockAlpha: Float = 0.7f
        val lockSize: Float = 0.5f // 50%

        val highlightFader = FaderUtil(0.0F, 0.05F, 0.25F)
        private val hoverFader = FaderUtil(0.0F, 0.05F, 0.25F)
        private val lockedHoverFader = FaderUtil(0.0F, 0.25F, 0.5F)
        private val clickFader = FaderUtil(0.0F, 0.05F, 0.25F)

        private var onClickFunctions: MutableList<(InputEventAPI) -> Unit> = ArrayList()
        private var onClickOutsideFunctions: MutableList<(InputEventAPI) -> Unit> = ArrayList()
        private var onClickReleaseFunctions: MutableList<(InputEventAPI) -> Unit> = ArrayList()
        private var onHoverFunctions: MutableList<(InputEventAPI) -> Unit> = mutableListOf()
        private var onHoverEnterFunctions: MutableList<(InputEventAPI) -> Unit> = mutableListOf()
        private var onHoverExitFunctions: MutableList<(InputEventAPI) -> Unit> = mutableListOf()

        val isUnlocked = paintjobSpec == null || paintjobSpec in MagicPaintjobManager.unlockedPaintjobs
        var isHovering = false
            private set

        var isSelected = false

        var hasClicked = false
            private set

        init{
            onClickFunctions.add {
                if(isUnlocked) clickFader.fadeIn()
            }
            onClickReleaseFunctions.add { clickFader.fadeOut() }
            onHoverEnterFunctions.add {
                if(isUnlocked) hoverFader.fadeIn()
                lockedHoverFader.fadeIn()
            }
            onHoverExitFunctions.add {
                clickFader.fadeOut()
                hoverFader.fadeOut()
                lockedHoverFader.fadeOut()
            }
        }

        override fun renderBelow(alphaMult: Float) {
            GL11.glPushMatrix()
            GL11.glDisable(GL11.GL_TEXTURE_2D)
            GL11.glDisable(GL11.GL_BLEND)

            // interpolate all the different faders together for the flash color
            var panelColor = Misc.interpolateColor(defaultBGColor, highlightBGColor, highlightFader.brightness)
            panelColor = Misc.interpolateColor(panelColor, hoverBGColor, hoverFader.brightness)
            panelColor = Misc.interpolateColor(panelColor, clickedBGColor, clickFader.brightness)
            val panelAlpha = panelColor.alphaf * alphaMult
            GL11.glColor4f(panelColor.redf, panelColor.greenf, panelColor.bluef, panelAlpha)
            GL11.glRectf(selectorPanel.left, selectorPanel.bottom, selectorPanel.right, selectorPanel.top)

            val darkerBorderColor = Misc.getDarkPlayerColor().darker()
            val darkerBorderAlpha = darkerBorderColor.alphaf * alphaMult
            GL11.glColor4f(darkerBorderColor.redf, darkerBorderColor.greenf, darkerBorderColor.bluef, darkerBorderAlpha)
            drawBorder(selectorPanel.left, selectorPanel.bottom, selectorPanel.right, selectorPanel.top)

            GL11.glPopMatrix()
        }

        override fun render(alphaMult: Float) {
            GL11.glPushMatrix()

            GL11.glDisable(GL11.GL_TEXTURE_2D)
            GL11.glEnable(GL11.GL_BLEND)
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA)
            if (!isUnlocked) {
                val lockedAlpha = Misc.interpolate(lockedColor.alphaf, 0f, lockedHoverFader.brightness) * alphaMult
                GL11.glColor4f(lockedColor.redf, lockedColor.greenf, lockedColor.bluef, lockedAlpha)
                GL11.glRectf(selectorPanel.left, selectorPanel.bottom, selectorPanel.right, selectorPanel.top)

                val lockedSprite = Global.getSettings().getSprite("icons", "lock")
                lockedSprite.alphaMult = Misc.interpolate(lockAlpha, 0f, lockedHoverFader.brightness)
                val scaleFactor = lockSize * selectorPanel.width / max(lockedSprite.width, lockedSprite.height)
                if (scaleFactor < 1)
                    lockedSprite.setSize(scaleFactor*lockedSprite.width, scaleFactor*lockedSprite.height)
                lockedSprite.renderAtCenter(selectorPanel.centerX, selectorPanel.top - selectorPanel.width/2)
            }
            GL11.glPopMatrix()
        }

        private fun drawBorder(x1: Float, y1: Float, x2: Float, y2: Float){
            GL11.glRectf(x1, y1, x2 + 1, y1 - 1)
            GL11.glRectf(x2, y1, x2 + 1, y2 + 1)
            GL11.glRectf(x1, y2, x1 - 1, y1 - 1)
            GL11.glRectf(x2, y2, x1 - 1, y2 + 1)
        }

        override fun processInput(events: MutableList<InputEventAPI>?) {
            events!!.filter { it.isMouseEvent }.forEach { event ->

                val inElement = event.x.toFloat() in selectorPanel.left..selectorPanel.right &&
                        event.y.toFloat() in selectorPanel.bottom..selectorPanel.top
                if (inElement) {
                    for (onHover in onHoverFunctions) onHover(event)
                    if (!isHovering) onHoverEnterFunctions.forEach { it(event) }
                    isHovering = true
                    if (event.isMouseDownEvent) {
                        hasClicked = true
                        onClickFunctions.forEach { it(event) }
                    }
                    if (event.isMouseUpEvent && hasClicked){
                        hasClicked = false
                        onClickReleaseFunctions.forEach { it(event) }
                    }
                } else {
                    if (isHovering) onHoverExitFunctions.forEach { it(event) }
                    isHovering = false
                    if (event.isMouseDownEvent) {
                        onClickOutsideFunctions.forEach { it(event) }
                    }
                    if (event.isMouseUpEvent){
                        hasClicked = false
                    }
                }
            }
        }

        fun onClick(function: (InputEventAPI) -> Unit) { onClickFunctions.add(function) }
        fun onClickRelease(function: (InputEventAPI) -> Unit) { onClickReleaseFunctions.add(function) }
        fun onClickOutside(function: (InputEventAPI) -> Unit) { onClickOutsideFunctions.add(function) }
        fun onHover(function: (InputEventAPI) -> Unit) { onHoverFunctions.add(function) }
        fun onHoverEnter(function: (InputEventAPI) -> Unit) { onHoverEnterFunctions.add(function) }
        fun onHoverExit(function: (InputEventAPI) -> Unit) { onHoverExitFunctions.add(function) }

        override fun advance(amount: Float) {
            highlightFader.advance(amount)
            hoverFader.advance(amount)
            lockedHoverFader.advance(amount)
            clickFader.advance(amount)

            if(isSelected) highlightFader.fadeIn()
            else highlightFader.fadeOut()
        }
    }

    internal fun createPaintjobSelector(hullVariantSpec: HullVariantSpec, paintjobSpec: MagicPaintjobSpec?,
                                        width: Float): CustomPanelAPI {
        val descriptionHeight = 45f
        val topPad = 5f

        val plugin = MagicPaintjobSelectorPlugin(paintjobSpec)
        val selectorPanel = Global.getSettings().createCustom(width, width+descriptionHeight, plugin)
        plugin.selectorPanel = selectorPanel

        val shipPreview = createShipPreview(hullVariantSpec, paintjobSpec, width, width)
        selectorPanel.addComponent(shipPreview).inTL(0f, topPad)

        val textElement = selectorPanel.createUIElement(width, descriptionHeight-topPad, false)
        selectorPanel.addUIElement(textElement)
        with(textElement){
            position.inTL(0f, width+topPad)
            setTitleOrbitronLarge()
            addTitle(paintjobSpec?.name ?: MagicTxt.getString("ml_mp_default"))
            addPara(paintjobSpec?.description ?: MagicTxt.getString("ml_mp_refit_defaultDesc"), 3f)
        }

        return selectorPanel
    }

    private fun createShipPreview(hullVariantSpec: HullVariantSpec, basePaintjobSpec: MagicPaintjobSpec?,
                                  width: Float, height: Float): UIPanelAPI {

        val clonedVariant = hullVariantSpec.clone()
        MagicPaintjobManager.removePaintjobFromShip(clonedVariant)
        clonedVariant.moduleVariants?.values?.forEach { moduleVariant ->
            MagicPaintjobManager.removePaintjobFromShip(moduleVariant as ShipVariantAPI)
        }

        val shipPreview = ReflectionUtils.instantiate(MagicPaintjobCombatRefitAdder.SHIP_PREVIEW_CLASS!!)!!
        ReflectionUtils.invoke("setVariant", shipPreview, clonedVariant)
        ReflectionUtils.invoke("overrideVariant", shipPreview, clonedVariant)
        ReflectionUtils.invoke("setShowBorder", shipPreview, false)
        ReflectionUtils.invoke("setScaleDownSmallerShipsMagnitude", shipPreview, 1f)
        ReflectionUtils.invoke("adjustOverlay", shipPreview, 0f, 0f)
        (shipPreview as UIPanelAPI).setSize(width, height)

        // make the ship list so the ships exist when we try and get them
        ReflectionUtils.invoke("prepareShip", shipPreview)

        // if the paintjob exists, replace the sprites
        basePaintjobSpec?.let { paintjob ->
            for(ship in ReflectionUtils.get(MagicPaintjobCombatRefitAdder.SHIPS_FIELD!!, shipPreview) as Array<ShipAPI>){
                MagicPaintjobManager.getPaintjobsForHull(ship.hullSpec).firstOrNull {
                    it.paintjobFamily?.equals(paintjob.paintjobFamily) == true || it.id == paintjob.id
                }?.let { MagicPaintjobManager.applyPaintjob(ship, it) }
            }
        }

        return shipPreview
    }
}
