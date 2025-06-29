package org.magiclib.bounty.intel

import org.magiclib.bounty.MagicBountyCoordinator
import org.magiclib.bounty.MagicBountyLoader

class MagicBountyBoardProvider: BountyBoardProvider {
    override fun getBounties(): List<BountyInfo> {
        return (MagicBountyLoader.BOUNTIES + MagicBountyCoordinator.getInstance().activeBounties.map { it.key to it.value.spec }.toMap())
            .entries
            .distinctBy { it.key }
            .map { (key, spec) ->
                    MagicBountyInfo(key, spec)
            }
    }
}