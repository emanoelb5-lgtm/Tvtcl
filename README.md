# TCL Light 0.6 — Android TV

Launcher nativo e offline para Android TV, com atenção à TCL 40S6500FS.

[**Baixar APK mais recente**](../../releases/download/beta/TCL-Light-beta.apk) · [Releases](../../releases/tag/beta) · [Compilações e testes](../../actions)

## Nova interface

- Central de ações em painel lateral, com atalhos grandes para rede, dispositivos, TV e modo leve; armazenamento, personalização e demais ações logo abaixo.
- Barra superior compacta, ícones vetoriais consistentes, tipografia legível à distância e foco com contorno claro e animação curta.
- Cartões de aplicativos em 16:9 aproveitam banners publicados pelos próprios apps. Quando não há banner, usam o ícone original.
- Cinco fotografias reais 1280×720 WebP, embaladas no APK, com transição suave a cada dois minutos e escurecimento ajustável. Sem frases sobre o fundo.
- Personalização, opções de apps e assistente Home seguem o mesmo padrão visual da central.

## Fluidez

- Fundos e miniaturas são carregados fora da thread da interface. Só os cartões visíveis carregam imagens; os que saem da tela liberam sua imagem. Miniaturas limitadas a 320×180 ou ícones 128×128, com cache de 4 MiB compartilhado pelas duas seções.
- Retornar de outro app não recria toda a tela: a grade permanece e a linha de mais utilizados só muda quando sua ordem muda.
- Instalações e alterações de apps, rede e periféricos atualizam por eventos; sem varredura periódica de aplicativos.
- Relógio atualizado por minuto. Fundos e observadores de estado param quando a tela fica invisível; executores são encerrados junto com a Activity.
- Modo leve desativa o zoom de foco e a rotação de fotos. Ativado inicialmente quando o Android identifica pouca RAM; pode ser alterado na central.
- Interface com Views nativas, sem WebView, vídeos de fundo, blur ou novas bibliotecas de execução. Bibliotecas AndroidX de teste entram apenas no APK de instrumentação.

## Aplicativos e controle

- Mostra apps lançáveis instalados pelo usuário, inclusive gerenciadores de arquivos, e streamings pré-instalados. Demais serviços e configurações de sistema ficam fora da grade.
- Mais utilizados são ordenados pelas aberturas feitas pelo launcher. OK longo permite fixar, ocultar e abrir informações do app. Os dados ficam locais.
- MENU abre/fecha a central; Voltar retorna um nível no painel e restaura o foco da tela inicial ao fechá-lo. Setas percorrem todos os controles sem escapar do painel.
- Assistente para escolher o launcher como Home, conforme suporte do firmware. Não requer root nem desativa a tela original.

## Compilação e verificação

GitHub Actions compila o aplicativo e o APK de instrumentação e executa seis cenários em Android TV API 28, 720p: foco com D-pad; navegação de personalização; ocultar/restaurar um app de arquivos; retorno das configurações sem recriar a grade; modo leve e aberturas repetidas; capturas nativas 16:9. Relatórios, capturas e diagnósticos ficam no artefato `TCL-Light-TV-QA`. A publicação da beta ocorre apenas em `main`, após os testes.

`applicationId=com.b5solucoes.tcllight` · `versionCode=6` · `versionName=0.6.0` · `minSdk=23` · `targetSdk=28` · `compileSdk=35`.

A assinatura de depuração continua usando o mesmo cache das versões anteriores. O cache pode expirar; uma assinatura release privada ainda é necessária para distribuição definitiva. Não é uma versão destinada à Play Store atual.

Para HDMI, use INPUT/SOURCE do controle: a troca direta depende das permissões do fabricante. O painel lista **dispositivos de entrada detectados**, não todos os acessórios Bluetooth pareados. Abra os ajustes para parear ou consultar acessórios.

As fotos são preparadas na compilação a partir do Unsplash; consulte [autoria e licença](NOTICE_WALLPAPERS.md). O aplicativo não precisa de Internet para funcionar. O comportamento e o desempenho no aparelho físico ainda dependem do firmware e do hardware da TV.
