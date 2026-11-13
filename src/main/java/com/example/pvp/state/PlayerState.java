package com.example.pvp.state;

import com.example.pvp.combat.CombatRules;
import com.example.pvp.combat.Dice;
import com.example.pvp.growth.GrowthRules;
import com.example.pvp.scenario.Scenario;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 单个玩家的服务器侧状态（GlobalState 内部使用，绝不直接发给玩家）。
 *
 * <p>HP 由裁决层修改（攻击命中扣血，最低 0）；AC、攻击加值、伤害骰来自剧本，缺省用
 * {@link CombatRules} 的默认值。inventory 会随裁决增长。</p>
 *
 * <p>成长由裁决层通过 {@link #gainExp(int, int)} 结算：经验溢出会连续升级，
 * 每次升级 maxHp +5、攻击加值 +1，并把 HP 回满。</p>
 */
public class PlayerState {

    private final String playerId;

    /** 当前 HP，只能由裁决层通过 {@link #applyDamage(int)} / {@link #gainExp(int, int)} 修改。 */
    private int hp;

    /** 最大 HP；升级时会增加，因此不是 final。 */
    private int maxHp;

    private final String secretGoal;

    /** 阵营标识；剧本未声明时为 null（视为无阵营）。 */
    private final String faction;

    private final List<String> skills;

    private final int armorClass;

    /** 命中检定加值；升级时会增加，因此不是 final。 */
    private int attackBonus;

    private final Dice damageDice;

    /** 当前累计经验（升级后保留溢出部分）。 */
    private int exp;

    /** 当前等级，初始为 {@link GrowthRules#INITIAL_LEVEL}。 */
    private int level;

    /** 该玩家持有的物品；读多写少，用 CopyOnWriteArrayList 保证遍历安全。 */
    private final List<String> inventory;

    /**
     * 按剧本初始设定创建玩家状态。
     *
     * @param playerId 玩家标识，如 "A" / "B"
     * @param spec     该玩家的剧本初始设定
     */
    public PlayerState(String playerId, Scenario.PlayerSpec spec) {
        this.playerId = playerId;
        this.hp = spec.hp();
        this.maxHp = spec.maxHp();
        this.secretGoal = spec.secretGoal();
        this.faction = spec.faction();
        this.skills = List.copyOf(spec.skills());
        this.armorClass = spec.armorClass() == null ? CombatRules.DEFAULT_ARMOR_CLASS : spec.armorClass();
        this.attackBonus = spec.attackBonus() == null ? CombatRules.DEFAULT_ATTACK_BONUS : spec.attackBonus();
        this.damageDice = Dice.parse(spec.damageDice() == null
                ? CombatRules.DEFAULT_DAMAGE_DICE : spec.damageDice());
        this.inventory = new CopyOnWriteArrayList<>(spec.items());
        this.exp = 0;
        this.level = GrowthRules.INITIAL_LEVEL;
    }

    /**
     * 玩家标识。
     *
     * @return 玩家标识
     */
    public String playerId() {
        return playerId;
    }

    /**
     * 当前 HP。
     *
     * @return 当前 HP
     */
    public int hp() {
        return hp;
    }

    /**
     * 最大 HP。
     *
     * @return 最大 HP
     */
    public int maxHp() {
        return maxHp;
    }

    /**
     * 护甲等级。
     *
     * @return AC
     */
    public int armorClass() {
        return armorClass;
    }

    /**
     * 命中检定加值。
     *
     * @return attackBonus
     */
    public int attackBonus() {
        return attackBonus;
    }

    /**
     * 伤害骰。
     *
     * @return 伤害骰规格
     */
    public Dice damageDice() {
        return damageDice;
    }

    /**
     * 承受伤害，HP 最低为 {@link CombatRules#MIN_HP}。
     *
     * @param damage 伤害值（>= 0）
     * @return 扣血后的剩余 HP
     */
    public synchronized int applyDamage(int damage) {
        hp = Math.max(CombatRules.MIN_HP, hp - damage);
        return hp;
    }

    /**
     * 是否已经倒下（HP 为 0）。
     *
     * @return true 表示 HP 归零
     */
    public boolean isDefeated() {
        return hp <= CombatRules.MIN_HP;
    }

    /**
     * 当前累计经验（已达升级阈值时会在结算时立即消费并保留溢出）。
     *
     * @return 当前经验
     */
    public int exp() {
        return exp;
    }

    /**
     * 当前等级。
     *
     * @return 等级，初始为 1
     */
    public int level() {
        return level;
    }

    /**
     * 结算经验并按阈值连续升级（经验溢出会一次升多级）。
     *
     * <p>每次升级：maxHp +5、攻击加值 +1；只要有升级，HP 就回满。</p>
     *
     * @param amount     本次获得的经验（>= 0）
     * @param expPerLevel 每级所需经验
     * @return 本次结算前后的完整数值快照，供事件审计与日志使用
     */
    public synchronized LevelUp gainExp(int amount, int expPerLevel) {
        int expBefore = exp;
        int levelBefore = level;
        int maxHpBefore = maxHp;
        int attackBonusBefore = attackBonus;
        int hpBefore = hp;

        exp += Math.max(0, amount);
        int levelsGained = 0;
        while (expPerLevel > 0 && exp >= expPerLevel) {
            exp -= expPerLevel;
            level++;
            maxHp += GrowthRules.LEVEL_UP_MAX_HP_BONUS;
            attackBonus += GrowthRules.LEVEL_UP_ATTACK_BONUS;
            levelsGained++;
        }
        if (levelsGained > 0) {
            hp = maxHp;
        }
        return new LevelUp(amount, expBefore, exp, levelBefore, level, levelsGained,
                maxHpBefore, maxHp, attackBonusBefore, attackBonus, hpBefore, hp);
    }

    /**
     * 顺手判断背包里是否已有某件物品（经验结算去重使用）。
     *
     * @param item 物品标识
     * @return true 表示已持有
     */
    public boolean hasItem(String item) {
        return inventory.contains(item);
    }

    /**
     * 一次经验结算的数值快照。
     *
     * @param expGained         本次获得的经验
     * @param expBefore         结算前经验
     * @param exp               结算后剩余经验（已扣除升级消耗）
     * @param levelBefore       结算前等级
     * @param level             结算后等级
     * @param levelsGained      本次提升的级数（可能 > 1）
     * @param maxHpBefore       结算前 maxHp
     * @param maxHpAfter        结算后 maxHp
     * @param attackBonusBefore 结算前攻击加值
     * @param attackBonusAfter  结算后攻击加值
     * @param hpBefore          结算前 HP
     * @param hpAfter           结算后 HP（有升级时为回满值）
     */
    public record LevelUp(int expGained, int expBefore, int exp, int levelBefore, int level, int levelsGained,
                          int maxHpBefore, int maxHpAfter, int attackBonusBefore, int attackBonusAfter,
                          int hpBefore, int hpAfter) {

        /**
         * 本次结算是否触发了升级。
         *
         * @return true 表示至少升了一级
         */
        public boolean leveledUp() {
            return levelsGained > 0;
        }
    }

    /**
     * 秘密目标，只允许出现在本人视角。
     *
     * @return 秘密目标文本
     */
    public String secretGoal() {
        return secretGoal;
    }

    /**
     * 阵营标识。
     *
     * @return 阵营；剧本未声明时为 null
     */
    public String faction() {
        return faction;
    }

    /**
     * 初始技能快照。
     *
     * @return 不可变的技能列表
     */
    public List<String> skills() {
        return skills;
    }

    /**
     * 该玩家当前持有的物品快照。
     *
     * @return 不可变的物品列表
     */
    public List<String> inventory() {
        return List.copyOf(inventory);
    }

    /**
     * 向该玩家背包加入一件物品。
     *
     * @param item 物品标识
     */
    public void addItem(String item) {
        inventory.add(item);
    }
}
