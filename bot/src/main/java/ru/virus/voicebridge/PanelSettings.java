package ru.virus.voicebridge;

/**
 * Настройки панели: картинка-подсказка и слова, по которым собираются сезонные подборки.
 *
 * <p>Слова вынесены в настройки, потому что подходящий запрос зависит от каталога и
 * вкуса: кому-то нужен «christmas», кому-то «новогодние песни».
 */
public record PanelSettings(String image, String winter, String spring, String autumn, String summer) {
}
