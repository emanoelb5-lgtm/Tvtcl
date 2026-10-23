# TCL Light 0.5 — Android TV premium

Launcher nativo para Android TV, priorizando a TCL 40S6500FS.

## O que há na v0.5
- Cinco **fotografias reais** de paisagens convertidas em WebP 1280×720, compactadas e embaladas no APK, alternando a cada 2 minutos (sem rede em tempo de execução).
- Perfis de foto fixa (1–5), alternância automática e fundo grafite; escurecimento de 20, 35, 50 ou 65%; ícones pequenos/normais/grandes; seleção de 5, 7 ou 10 mais utilizados.
- **Modo economia** desativa os efeitos de zoom no foco e a rotação de papel de parede.
- Mais utilizados ordenados por número de aberturas **a partir do launcher**, com opção de **fixar**, **ocultar** ou consultar informações via **OK longo**.
- Gerenciamento de aplicativos ocultos disponível em Personalização e Central de ações.
- Mostra apps lançáveis instalados pelo usuário, inclusive gerenciadores de arquivos, e streamings pré-instalados. Não mostra demais serviços de sistema.
- Central de ações pelo botão ⋯ ou MENU: rede, perifericos de entrada detectados, armazenamento livre, Wi-Fi, Bluetooth, TV, dicas de HDMI, ajuste de Home, personalização e Configurações do Android.
- Assistente para solicitar a configuração como launcher padrão; verifica a opção HOME quando o Android permite. Não requer root, não desativa sistema original.

## APK
[Releases Beta](../../releases/tag/beta) · [GitHub Actions](../../actions). `applicationId=com.b5solucoes.tcllight`; `versionCode=5`.

**Importante**: a assinatura da beta é de depuração, guardada no cache de GitHub Actions. Pode expirar. A escolha do launcher padrão continua sujeita às limitações do firmware TCL. Nunca desative o launcher original sem um plano de recuperação.

## Compatibilidade e notas técnicas
- `minSdk 23`, `targetSdk 28`; não é para Play Store atual.
- O indicador Bluetooth abre ajustes, pois ler a lista de dispositivos pareados exige permissões especiais em Android recente. A lista de periféricos é a dos **dispositivos de entrada detectados**.
- Para HDMI, use INPUT/SOURCE do controle. Não existe API universal de troca de entradas disponível a apps comuns em todas as TCL.
- Histórico de utilização é local, restrito às aberturas feitas por esta tela. Não pede acesso às estatísticas privadas de outros apps.
- Os cinco fundos são **gerados na compilação** a partir de fotografias Unsplash: veja [NOTICE_WALLPAPERS.md](NOTICE_WALLPAPERS.md). Se o fornecedor ficar indisponível, o build falha em vez de empacotar fundos ausentes.
- O mecanismo de Home pode ser bloqueado no firmware. Use a central de ações → Assistente de tela inicial e teste depois de reiniciar. Se não houver opção no Android, a troca precisa ser feita pelo sistema/ADB, se suportado. Não modifique o firmware.
