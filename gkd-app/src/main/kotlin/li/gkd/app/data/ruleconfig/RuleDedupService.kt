package li.gkd.app.data.ruleconfig

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import li.gkd.app.a11y.launcherAppId
import li.gkd.app.data.RawSubscription
import li.gkd.app.data.appinfo.AppInfoRepository
import li.gkd.app.data.subscription.SubscriptionRepository
import li.gkd.app.domain.rule.RuleGroupTarget
import li.gkd.db.SubsItem
import li.gkd.db.Db

/**
 * 规则去重服务:
 * - 全量开启所有订阅的所有应用规则组, 然后自动关闭重复的组(同app同类)
 * - 手动查重: 扫描并报告重复组信息
 *
 * 组指纹: 组内全部 rule 的 matches/anyMatches/action/activityIds 规范化拼接
 * 去重策略: 同 appId 内指纹相同的组, 仅保留第一个(按订阅 order 排序), 其余 disable
 */
object RuleDedupService {

    data class DedupResult(
        val enabledCount: Int,
        val duplicateCountClosed: Int,
        val duplicateCountByApp: Map<String, Int>,
    )

    /**
     * 一键开启所有规则 + 自动去重:
     * 1. 收集所有订阅的所有 app 组 → batch enable=true 全开
     * 2. 扫描同 appId 重复组 → batch enable=false 关重复
     */
    suspend fun enableAllAndDedup(): DedupResult = withContext(Dispatchers.IO) {
        val snapshot = SubscriptionRepository.awaitSnapshot()
        val systemAppIds = AppInfoRepository.systemAppsFlow.value

        // 收集所有 App 组的 RuleGroupTarget(跨所有订阅)
        val allAppTargets = mutableListOf<RuleGroupTarget>()
        for ((subsId, sub) in snapshot.subscriptions) {
            if (sub.id < 0) continue // 跳过本地/内存订阅
            for (app in sub.apps) {
                for (group in app.groups) {
                    if (group.valid) {
                        allAppTargets.add(RuleGroupTarget.App(subsId, app.id, group.key))
                    }
                }
            }
        }

        // 全开
        var totalEnabled = 0
        if (allAppTargets.isNotEmpty()) {
            totalEnabled = RuleGroupConfigService.batchUpdateGroupEnabled(
                allAppTargets.toSet(),
                true,
                launcherAppId,
                systemAppIds,
            ).size
        }

        // 去重: 同 app 指纹相同, 仅保留第一个(按 subs_item order), 其余关
        val items = Db.subsItemDao.queryAll().associateBy { it.id }
        // 构建 app → [(subsOrder, target)]
        val appGroups = mutableMapOf<String, MutableList<Pair<Int, RuleGroupTarget>>>()
        for (target in allAppTargets) {
            if (target !is RuleGroupTarget.App) continue
            val order = items[target.subsId]?.order ?: Int.MAX_VALUE
            appGroups.getOrPut(target.appId) { mutableListOf() }.add(order to target)
        }
        val toDisable = mutableListOf<RuleGroupTarget>()
        for ((appId, entries) in appGroups) {
            entries.sortBy { (order, _) -> order }
            val seen = mutableSetOf<String>()
            for ((_, target) in entries) {
                if (target !is RuleGroupTarget.App) continue
                val subs = snapshot.subscriptions[target.subsId] ?: continue
                val group = subs.apps
                    .find { a -> a.id == appId }
                    ?.groups
                    ?.find { g -> g.key == target.groupKey }
                    ?: continue
                val fp = groupFingerprint(group)
                if (!seen.add(fp)) {
                    toDisable.add(target)
                }
            }
        }

        var closed = 0
        if (toDisable.isNotEmpty()) {
            closed = RuleGroupConfigService.batchUpdateGroupEnabled(
                toDisable.toSet(),
                false,
                launcherAppId,
                systemAppIds,
            ).size
        }

        val dupByApp = toDisable.groupBy { (it as RuleGroupTarget.App).appId }
            .mapValues { (_, v) -> v.size }

        DedupResult(
            enabledCount = totalEnabled,
            duplicateCountClosed = closed,
            duplicateCountByApp = dupByApp,
        )
    }

    /**
     * 手动查重: 仅扫描重复组, 不修改 enable 状态, 返回重复统计
     */
    suspend fun findDuplicates(): Map<String, Int> = withContext(Dispatchers.IO) {
        val snapshot = SubscriptionRepository.awaitSnapshot()
        val items = Db.subsItemDao.queryAll().associateBy { it.id }
        val appDupCount = mutableMapOf<String, Int>()

        for ((appId, subsAndGroups) in groupGroupsByApp(snapshot)) {
            val entries = subsAndGroups
                .sortedWith(compareBy<Pair<Long, RawSubscription.RawAppGroup>> { (sId, _) ->
                    items[sId]?.order ?: Int.MAX_VALUE
                })
            val seen = mutableSetOf<String>()
            for ((_, group) in entries) {
                val fp = groupFingerprint(group)
                if (!seen.add(fp)) {
                    appDupCount[appId] = appDupCount.getOrDefault(appId, 0) + 1
                }
            }
        }
        appDupCount
    }

    private fun groupGroupsByApp(snapshot: li.gkd.app.data.subscription.SubscriptionSnapshot): Map<String, List<Pair<Long, RawSubscription.RawAppGroup>>> {
        val result = mutableMapOf<String, MutableList<Pair<Long, RawSubscription.RawAppGroup>>>()
        for ((subsId, sub) in snapshot.subscriptions) {
            if (sub.id < 0) continue
            for (app in sub.apps) {
                for (group in app.groups) {
                    if (group.valid) {
                        result.getOrPut(app.id) { mutableListOf() }.add(subsId to group)
                    }
                }
            }
        }
        return result
    }

    private fun groupFingerprint(group: RawSubscription.RawAppGroup): String {
        val ruleParts = group.rules
            .map { r ->
                listOfNotNull(
                    r.matches?.sorted()?.joinToString("||")?.let { "matches:$it" },
                    r.anyMatches?.sorted()?.joinToString("||")?.let { "anyMatches:$it" },
                    r.excludeMatches?.sorted()?.joinToString("||")?.let { "excludeMatches:$it" },
                    r.excludeAllMatches?.sorted()?.joinToString("||")?.let { "excludeAllMatches:$it" },
                    r.action?.let { "action:$it" },
                    r.activityIds?.let { "activityIds:$it" },
                ).joinToString("&")
            }
            .sorted()
        return ruleParts.joinToString("§")
    }
}