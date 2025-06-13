package org.magiclib.paintjobs.console

import org.lazywizard.console.BaseCommand
import org.lazywizard.console.Console
import org.magiclib.paintjobs.MagicPaintjobManager

class ListPaintjobsCommand : BaseCommand {
    override fun runCommand(args: String, context: BaseCommand.CommandContext): BaseCommand.CommandResult {
        val normalPjs = MagicPaintjobManager.getPaintjobs()
        val shinyPjs = MagicPaintjobManager.getPaintjobs(includeShiny = true) - normalPjs

        Console.showMessage("Regular Paintjobs")
        for (pj in normalPjs) {
            Console.showMessage("\t$pj")
        }

        Console.showMessage("Shiny Paintjobs")
        for (pj in shinyPjs) {
            Console.showMessage("\t$pj")
        }

        return BaseCommand.CommandResult.SUCCESS
    }
}