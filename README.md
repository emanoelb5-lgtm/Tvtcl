# TCL Light — Android TV minimalista

**Versão 0.3.0** · App nativo Kotlin para a TCL 40S6500FS e Android TV 6 ou superior.

## Experiência

- Tela inicial inspirada na organização do Google TV, mas com design próprio, sem menus de propaganda ou carregamento pela internet.
- Fundo original abstrato e cinematográfico, desenhado pelo Android Canvas, sem imagens pesadas nem reprodução em segundo plano.
- Cabeçalho simples com relógio, data, TV, atualizar e engrenagem das configurações.
- Configurações **não aparecem como aplicativo**.
- **Somente apps de streaming/mídia instalados**: apps de vídeo, TV, IPTV, áudio e música identificados pelos nomes, pacotes ou categoria de mídia Android. Nenhum cartão para serviços ausentes.
- Favoritos preservados de versões anteriores, quando continuam sendo serviços de mídia instalados. Segure OK em um app para incluir/remover.
- Navegação pelo controle remoto: setas, OK, pressionar OK, MENU (Configurações), voltar.
- Offline, sem permissões sensíveis, rastreamento ou anúncios.

## APK

O [GitHub Actions](../../actions) gera o APK e publica automaticamente em [Releases → Beta](../../releases/tag/beta) no push para `main`. O app usa o mesmo `applicationId` da versão anterior e `versionCode=3`. A assinatura permanece no mecanismo de cache da chave de debug atual; o Android só instalará sobre a versão antiga se a assinatura for a mesma. Para atualizações definitivas, adotar uma assinatura de release persistente em segredo privado.

## Compatibilidade e limites

- A classificação de apps de mídia usa um filtro conservador. Apps de streaming muito novos, com nomes/pacotes incomuns e categoria Android não definida podem não ser reconhecidos. Nenhum aplicativo é instalado ou criado artificialmente.
- Pressionar **TV** inicia um app de TV já instalado quando identificável. HDMI continua acessível pela tecla física INPUT/SOURCE do controle.
- Não desative o launcher original da TCL. Configurar um app como Home depende das restrições do firmware.
- Mudar launcher não acelera a decodificação ou o streaming dos outros aplicativos.
- Build de teste: `targetSdk=28`, não preparado para Play Store.

## Build local

Android SDK 35, Gradle 8.10.2, JDK 17. Executar `gradle :app:assembleDebug` ou baixar APK em Releases.
