package com.example.pvp.combat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 骰子规格（如 1d6），只做解析与校验，不负责随机。
 *
 * @param count 骰子数量
 * @param sides 骰子面数
 */
public record Dice(int count, int sides) {

    /** 形如 1d6 / 2d4 的写法。 */
    private static final Pattern NOTATION_PATTERN = Pattern.compile("(\\d{1,2})d(\\d{1,3})");

    /**
     * 解析骰子写法。
     *
     * @param text 形如 "1d6" 的字符串
     * @return 骰子规格
     * @throws IllegalArgumentException 写法非法或超出允许范围
     */
    public static Dice parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("伤害骰不能为空");
        }
        Matcher matcher = NOTATION_PATTERN.matcher(text.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("伤害骰写法非法（应形如 1d6）：" + text);
        }
        int count = Integer.parseInt(matcher.group(1));
        int sides = Integer.parseInt(matcher.group(2));
        if (count < CombatRules.MIN_DICE_COUNT || count > CombatRules.MAX_DICE_COUNT) {
            throw new IllegalArgumentException("伤害骰数量必须在 " + CombatRules.MIN_DICE_COUNT + "~"
                    + CombatRules.MAX_DICE_COUNT + " 之间：" + text);
        }
        if (sides < CombatRules.MIN_DICE_SIDES || sides > CombatRules.MAX_DICE_SIDES) {
            throw new IllegalArgumentException("伤害骰面数必须在 " + CombatRules.MIN_DICE_SIDES + "~"
                    + CombatRules.MAX_DICE_SIDES + " 之间：" + text);
        }
        return new Dice(count, sides);
    }

    /**
     * 标准写法，用于日志与事件审计。
     *
     * @return 如 "1d6"
     */
    public String notation() {
        return count + "d" + sides;
    }
}
