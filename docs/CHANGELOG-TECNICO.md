# Changelog Tecnico - SistPlantao

Este documento registra as alteracoes tecnicas mais importantes feitas na preparacao do app Android/PWA para release. Use como referencia antes de novas atualizacoes.

## 2026-05-15 - Preparacao Android, lembretes e persistencia

Commits principais:

- `767a082 feat: preparar lembretes nativos para release`
- `5a6b881 fix: usar som padrao nos lembretes`

### Estrutura atual do app Android

- O app Android abre a interface local em `file:///android_asset/index.html` por `LauncherActivity`, nao como TWA pura.
- O Gradle copia `index.html`, `manifest.json`, `offline.html`, `privacy.html`, `sw.js` e `icons/**` para `app/src/main/assets` antes do build.
- Quando mudar arquivos web da raiz, rode um build Android para atualizar os assets empacotados.

Comandos usados:

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
.\gradlew.bat lintRelease
.\gradlew.bat bundleRelease
```

### Lembretes e notificacoes

- `AlarmScheduler` agenda lembretes nativos com `AlarmManager`.
- `ShiftReminderReceiver` recebe o disparo do alarme e mostra uma notificacao comum.
- O som atual e o som padrao de notificacao do Android, semelhante a mensagem recebida.
- O antigo `AlarmSoundService` foi removido para evitar comportamento de despertador com som continuo.
- As permissoes de foreground service tambem foram removidas do manifest.
- `SystemEventReceiver` reagenda lembretes apos boot e quando a permissao de alarmes exatos muda.

Arquivos centrais:

- `app/src/main/java/br/com/sistplantao/app/AlarmScheduler.java`
- `app/src/main/java/br/com/sistplantao/app/ShiftReminderReceiver.java`
- `app/src/main/java/br/com/sistplantao/app/SystemEventReceiver.java`
- `app/src/main/java/br/com/sistplantao/app/NotificationHelper.java`
- `app/src/main/AndroidManifest.xml`

Cuidados:

- Nao reintroduzir som em loop ou `ForegroundService` sem revisar politica da Play Store.
- Se mudar o ID do canal `plantao_reminders_message_v1`, usuarios podem perder configuracoes do canal de notificacao.
- Em Android 12+, alarmes exatos dependem da permissao do sistema. A UI ja abre a tela de configuracao quando necessario.

### Persistencia da escala

- A escala principal continua em `localStorage` com a chave `plantao_pro_v36`.
- Tambem existe `plantao_pro_v36_backup` como backup web.
- No Android, o app grava uma copia nativa em `SharedPreferences` via `AndroidReminder.backupShifts(...)`.
- Ao abrir, se o `localStorage` estiver vazio, `index.html` tenta restaurar com `AndroidReminder.savedShifts()`.

Objetivo: reduzir risco de perder escala em atualizacoes do APK.

Importante:

- Atualizacao normal por cima preserva dados quando `applicationId` e assinatura continuam iguais.
- Desinstalar o app apaga os dados locais.
- Trocar assinatura entre builds pode impedir atualizacao por cima; nesse caso o usuario pode precisar desinstalar, perdendo dados.

### Politica de privacidade

- `privacy.html` foi adicionado na raiz e nos assets Android.
- O link "Voltar para o app" aponta para `./index.html`.
- Nao usar `href="./"` dentro do APK, porque vira `file:///android_asset/` e causa `net::ERR_FILE_NOT_FOUND`.

### Build, assinatura e arquivos gerados

Arquivos de teste/local:

- APK debug: `app/build/outputs/apk/debug/app-debug.apk`
- APK release unsigned: `app/build/outputs/apk/release/app-release-unsigned.apk`
- APK release assinado com debug para teste local: gerado manualmente com `apksigner`

O APK `app-release-unsigned.apk` nao deve ser instalado diretamente. Ele nao tem assinatura e o Android pode mostrar "pacote invalido".

Para teste local, assinar com debug:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" sign `
  --ks "$env:USERPROFILE\.android\debug.keystore" `
  --ks-key-alias androiddebugkey `
  --ks-pass pass:android `
  --key-pass pass:android `
  --out app\build\outputs\apk\release\app-release-message-sound-debug-signed.apk `
  app\build\outputs\apk\release\app-release-unsigned.apk
```

Verificar assinatura:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --verbose app\build\outputs\apk\release\app-release-message-sound-debug-signed.apk
```

Para Play Store:

- Usar `bundleRelease` e enviar `.aab`.
- Configurar chave real com `keystore.properties` local, baseado em `keystore.properties.example`.
- Nao versionar `.jks`, `.keystore` ou `keystore.properties`.

### Gradle e dependencias

- Repositorios migrados de `jcenter()` para `mavenCentral()`.
- `lintOptions` foi atualizado para `lint`.
- `lintRelease` deve passar antes de gerar pacote final.

### Pontos que merecem atencao na proxima atualizacao

- Se mudar a estrutura do calendario ou classes `.shift-chip` / `#cal-grid`, revisar a sincronizacao nativa em `LauncherActivity`.
- Se alterar `STORAGE_KEY`, implementar migracao para nao perder escalas antigas.
- Se alterar politica de notificacoes, testar em Android 13+ com permissao `POST_NOTIFICATIONS`.
- Se publicar na Play Store, revisar declaracoes de alarme exato e privacidade no Play Console.
- Depois de mudar `privacy.html`, sempre testar o link "Voltar para o app" dentro do APK.
