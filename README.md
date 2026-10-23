# TCL Light — v0.4.0 para Android TV

Launcher Kotlin nativo, sem anúncios, login, rede externa ou imagens remotas.

### Nova experiência
- **Aplicativos**: exibe aplicativos instalados pelo usuário (inclusive gerenciadores de arquivos, players e utilitários) e aplicativos de mídia/streaming pré-instalados. Oculta o restante dos apps de sistema, Configurações e launchers.
- **Mais utilizados**: primeira linha ordenada pelo número de vezes que cada app foi aberto a partir deste launcher (desempate pelo acesso recente). Atualiza quando você retorna à tela inicial. Não lê a atividade privada de outros aplicativos.
- **Central superior**: status de conexão (Wi-Fi, Ethernet ou sem rede), quantidade de entradas de controle/periféricos de entrada detectadas, TV, seleção de tela inicial padrão, atualização e Configurações. Não lê SSID, nome de Bluetooth ou dados pessoais.
- **Papel de parede**: três paisagens artísticas locais desenhadas pelo Android, alternadas a cada 45 segundos com transição suave. Não usa fotos externas nem vídeo contínuo.
- **Sem banner ou slogan** sobreposto ao fundo.
- **Launcher de verdade**: já responde à categoria HOME. O botão ⌂ abre a solicitação do papel de tela inicial no Android 10+ ou a tela de apps Home em versões anteriores. A TCL pode restringir essa escolha. Não desative o launcher original; sempre mantenha o caminho de retorno.

### Instalação
- APK beta: [Releases](../../releases/tag/beta). Mesmo pacote `com.b5solucoes.tcllight`, `versionCode=4`; instalação sobre versão anterior depende de assinatura compatível.
- A execução [Actions](../../actions) gera `TCL-Light-beta.apk` e publica em Releases.
- Assinatura **debug** permanece guardada em cache GitHub; pode expirar e não garante atualizações indefinidas. Para lançamento estável, configurar chave de assinatura de release preservada como segredo.
- A atividade HOME abre automaticamente após seleção apenas em versões/firmwares que permitam trocar a tela inicial por aplicativos de terceiros.

### Limitações
- Mostra somente apps com entrada de launcher; serviços sem Activity lançável não entram.
- 'Mais utilizados' mede aberturas pela própria tela inicial, não uso em minutos nem aberturas externas.
- A central mostra dispositivos de entrada detectados pelo Android (não necessariamente todo periférico Bluetooth emparelhado).
- O atalho TV exige um app TV detectável; HDMI continua no botão INPUT/SOURCE.
- `minSdk=23`, `targetSdk=28` apenas para compatibilidade com a TCL antiga, não adequado à publicação atual na Play Store.
