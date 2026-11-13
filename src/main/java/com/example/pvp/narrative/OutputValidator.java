package com.example.pvp.narrative;

import com.example.pvp.state.GlobalState;
import com.example.pvp.state.PlayerView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 输出校验层：拒绝任何可能泄露他人信息的叙事文本，一律降级为模板化安全叙事。
 *
 * <p>只判定两类违规（不再按玩家标识做子串匹配，避免中文叙事里的单字母误杀）：</p>
 * <ol>
 *   <li>文本出现了本玩家看不见的私有物品（对方背包里有、我的背包里没有），中英文别名都要覆盖；</li>
 *   <li>文本出现了本玩家视角之外的机制数字（骰子点数、血量变化等），除非该数值确实出现在其 PlayerView 里。</li>
 * </ol>
 *
 * <p>校验失败绝不把原文推给玩家。</p>
 */
@Component
public class OutputValidator {

    private static final Logger log = LoggerFactory.getLogger(OutputValidator.class);

    /** 降级叙事：任何校验失败或调用失败都用这一句。 */
    public static final String SAFE_NARRATIVE = "周围很安静，什么也没发生。";

    /** 叙事里的数字片段；用于判断是否出现视角外的机制数字。 */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");

    /**
     * 校验叙事文本；不合格或为空时返回安全模板。
     *
     * @param state     该玩家所在房间的全局状态（提供私有物品边界）
     * @param view      该玩家的视角投影（提供可见信息边界）
     * @param narrative 模型返回的原始叙事，可能为 null（调用失败）
     * @return 通过校验的原文，或安全模板
     */
    public String validateOrFallback(GlobalState state, PlayerView view, String narrative) {
        if (narrative == null || narrative.isBlank()) {
            log.warn("叙事校验降级 player={} 原因=叙事为空或调用失败", view.playerId());
            return SAFE_NARRATIVE;
        }

        String hiddenItemHit = findHiddenItemHit(state, view, narrative);
        if (hiddenItemHit != null) {
            log.warn("叙事校验降级 player={} 原因=文本出现本玩家看不见的私有物品 命中词={} text={}",
                    view.playerId(), hiddenItemHit, narrative);
            return SAFE_NARRATIVE;
        }

        String numberHit = findOutOfViewNumber(view, narrative);
        if (numberHit != null) {
            log.warn("叙事校验降级 player={} 原因=文本出现本玩家视角外的机制数字 命中数字={} text={}",
                    view.playerId(), numberHit, narrative);
            return SAFE_NARRATIVE;
        }

        return narrative;
    }

    /**
     * 查找文本里是否出现了对方私有物品的任何别名。
     *
     * @return 命中的自然语言说法；未命中返回 null
     */
    private String findHiddenItemHit(GlobalState state, PlayerView view, String narrative) {
        for (String item : state.hiddenItemsFrom(view.playerId())) {
            for (String alias : Terminology.itemAliases(item)) {
                if (narrative.contains(alias)) {
                    return alias;
                }
            }
        }
        return null;
    }

    /**
     * 查找文本里是否出现了该玩家 PlayerView 之外的数字。
     *
     * <p>允许集来自 PlayerView 中 myInventory / visibleEvents 的全部字段，
     * 保证“该数值确实出现在该玩家的 PlayerView 里”的数字不会被误判。</p>
     *
     * @return 命中的数字片段；未命中返回 null
     */
    private String findOutOfViewNumber(PlayerView view, String narrative) {
        Set<String> allowed = allowedNumbers(view);
        Matcher matcher = NUMBER_PATTERN.matcher(narrative);
        while (matcher.find()) {
            String number = matcher.group();
            if (!allowed.contains(number)) {
                return number;
            }
        }
        return null;
    }

    private Set<String> allowedNumbers(PlayerView view) {
        Set<String> allowed = new LinkedHashSet<>();
        allowed.add(String.valueOf(view.hp()));
        allowed.add(String.valueOf(view.maxHp()));
        allowed.add(String.valueOf(view.exp()));
        allowed.add(String.valueOf(view.level()));
        allowed.add(String.valueOf(view.expPerLevel()));
        allowed.add(String.valueOf(view.turn()));
        view.myInventory().forEach(item -> collectNumbers(item, allowed));
        view.publicPlayers().forEach(player -> {
            collectNumbers(player.playerId(), allowed);
            allowed.add(String.valueOf(player.level()));
            allowed.add(String.valueOf(player.hp()));
            allowed.add(String.valueOf(player.maxHp()));
            allowed.add(String.valueOf(player.armorClass()));
        });
        view.visibleEvents().forEach(event -> {
            collectNumbers(event.eventId(), allowed);
            collectNumbers(event.intent(), allowed);
            collectNumbers(event.result(), allowed);
            collectCombatNumbers(event.combat(), allowed);
            collectGrowthNumbers(event.growth(), allowed);
        });
        return allowed;
    }

    /**
     * 成长审计字段同样属于该玩家可见信息：升级叙事引用等级、maxHp、攻击加值等数字是合法的。
     * 模型自行编造的其他数字会被拒绝并降级。
     */
    private void collectGrowthNumbers(com.example.pvp.state.GameEvent.Growth growth, Set<String> sink) {
        if (growth == null) {
            return;
        }
        sink.add(String.valueOf(growth.expGained()));
        sink.add(String.valueOf(growth.expBefore()));
        sink.add(String.valueOf(growth.exp()));
        sink.add(String.valueOf(growth.levelBefore()));
        sink.add(String.valueOf(growth.level()));
        sink.add(String.valueOf(growth.levelsGained()));
        sink.add(String.valueOf(growth.maxHpBefore()));
        sink.add(String.valueOf(growth.maxHpAfter()));
        sink.add(String.valueOf(growth.attackBonusBefore()));
        sink.add(String.valueOf(growth.attackBonusAfter()));
        sink.add(String.valueOf(growth.hpBefore()));
        sink.add(String.valueOf(growth.hpAfter()));
    }

    /**
     * 战斗审计字段同样属于该玩家可见信息：叙事引用这些数字是合法的。
     * 任何不在视图里的数字（例如模型自行编造的伤害）都会被拒绝。
     */
    private void collectCombatNumbers(com.example.pvp.state.GameEvent.Combat combat, Set<String> sink) {
        if (combat == null) {
            return;
        }
        sink.add(String.valueOf(combat.attackRoll()));
        sink.add(String.valueOf(combat.attackBonus()));
        sink.add(String.valueOf(combat.attackTotal()));
        sink.add(String.valueOf(combat.targetAc()));
        collectNumbers(combat.damageDice(), sink);
        sink.add(String.valueOf(combat.damageRoll()));
        sink.add(String.valueOf(combat.damage()));
        sink.add(String.valueOf(combat.targetHpRemaining()));
    }

    private void collectNumbers(String text, Set<String> sink) {
        if (text == null) {
            return;
        }
        Matcher matcher = NUMBER_PATTERN.matcher(text);
        while (matcher.find()) {
            sink.add(matcher.group());
        }
    }
}
