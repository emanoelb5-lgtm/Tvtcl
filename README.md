# TCL Light — launcher leve para Android TV

Projeto nativo em **Kotlin**, criado para a TV **TCL 40S6500FS** e outras TVs compatíveis com Android TV (Android 6+). Interface escura e simples, sem anúncios, WebView, tracking ou permissão de internet. Não modifica o firmware.

## APK pronto pelo GitHub Actions

1. Acesse a aba [Actions](../../actions) e abra **Compilar APK TCL Light**.
2. Abra a execução mais recente concluída com sucesso e baixe o artefato **TCL-Light-APK**.
3. Também é possível baixar o `TCL-Light-beta.apk` na aba [Releases](../../releases/tag/beta) após o primeiro build bem-sucedido.
4. Instale via pendrive, app gerenciador de arquivos ou ADB.

O workflow roda automaticamente ao publicar na branch `main` ou manualmente pelo botão **Run workflow**. Usa JDK 17, Gradle 8.10.2, Android SDK 35.

## Recursos

- Navegação com controle remoto: setas, OK, Voltar e MENU.
- Grade de apps instalados e seção de favoritos (segure OK para alterar).
- Atalhos para TV/Canais (quando aplicativo visível), Configurações e Atualizar.
- Relógio, interface premium leve com elementos nativos.
- Sem conexão de rede, anúncios, analytics, conta ou permissões sensíveis.

## Instalação e reversão

**Teste primeiro como aplicativo comum**. Não remova ou desative os launchers originais TCL/Google. Abra TCL Light na lista de apps, teste navegação, reprodução de apps e acesso às Configurações. Caso o Android TV ofereça escolher o app Home, selecione TCL Light. Alguns firmwares da TCL ignoram apps Home de terceiros — essa limitação não pode ser contornada com segurança em todos os modelos.

A tecla INPUT/SOURCE original continua sendo o modo mais confiável de mudar as entradas HDMI.

## Limitações da versão beta

- Ainda não foi testado em TV real.
- Não corrige lentidão interna de outros aplicativos nem adiciona RAM.
- O APK inicial usa **assinatura de teste**. O workflow guarda sua chave no cache para facilitar a atualização, mas o cache pode expirar; para atualizações confiáveis de longo prazo será necessário configurar assinatura privada release.
- Esta versão mantém **targetSdk 28** como opção de compatibilidade com o Android TV antigo; não é uma versão pronta para publicação na Play Store.

## Build local

Abra no Android Studio com JDK 17 e SDK 35 ou execute `gradle :app:assembleDebug` com Gradle 8.10.2.

Código fonte inicial. Licença de distribuição não especificada.
