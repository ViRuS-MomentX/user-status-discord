# Патч к CustomRP

`customrp-voice-bridge.patch` накладывается на `maximmax42/Discord-CustomRP`,
коммит `935879fb5ee87d60d4521489e1c811cc94e1f874` (записан в `BASE_COMMIT`).

```
git clone https://github.com/maximmax42/Discord-CustomRP
cd Discord-CustomRP
git checkout 935879fb5ee87d60d4521489e1c811cc94e1f874
git apply ../customrp/customrp-voice-bridge.patch
```

Собирать в Visual Studio: `CustomRPC.sln`, .NET Framework 4.8.

Подробности — в README в корне репозитория.

## Что меняется

Новые файлы:

* `CustomRPC/VoiceBridge.cs` — клиент бота: опрос, разбор JSON, событие при смене состояния;
* `CustomRPC/VoiceBridgeForm.cs` и `.Designer.cs` — окно настроек.

Правки существующих файлов:

* `MainForm.cs` — поле `voiceBridge`, подписка в конструкторе, вызов `ApplyVoiceStatus()`
  в `SetPresence()` и четыре новых метода;
* `MainForm.Designer.cs` — пункт меню «Статус голосового канала»;
* `Properties/Settings.settings` и `.Designer.cs` — шесть новых настроек с префиксом `voice`;
* `Strings.resx`, `Strings.ru.resx`, `Strings.Designer.cs` — 18 строк интерфейса
  (английские и русские; остальные языки откатятся на английский);
* `CustomRPC.csproj` — новые файлы и ссылка на `System.Web.Extensions`
  (оттуда берётся `JavaScriptSerializer` для разбора JSON).

Апстриму такой патч предлагать бессмысленно: фича требует отдельного бота и
нужна ровно одному человеку.
