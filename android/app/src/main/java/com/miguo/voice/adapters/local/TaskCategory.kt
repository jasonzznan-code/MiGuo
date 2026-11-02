package com.miguo.voice.adapters.local

/**
 * 米果任务分类六类（契约 §3.3 产品枚举写死）。
 * 自建枚举，不引用 nano Ideas / GoalCategory。
 */
enum class TaskCategory {
    travel,
    work,
    life,
    learn,
    social,
    ideas,
    ;

    val labelZh: String
        get() = when (this) {
            travel -> "出行"
            work -> "工作"
            life -> "生活"
            learn -> "学习"
            social -> "社会关系"
            ideas -> "点子"
        }

    companion object {
        /** 计划页折叠顺序 */
        val displayOrder: List<TaskCategory> = entries.toList()
    }
}
