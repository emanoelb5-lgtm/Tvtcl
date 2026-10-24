# TCL Light 0.13.0

Launcher nativo em Kotlin para Android TV, com foco em controle remoto e TVs com pouca memória.

[Baixar TCL-Light-0.13.0.apk](https://github.com/emanoelb5-lgtm/Tvtcl/releases/download/v0.13.0/TCL-Light-0.13.0.apk) · [Versões](https://github.com/emanoelb5-lgtm/Tvtcl/releases)

## Instalação e atualizações

Se você já usa a **0.7.0 a 0.12.0, instale a 0.13.0 por cima**, sem desinstalar. O pacote e a assinatura permanente são os mesmos; a organização, os ajustes e a escolha de tela inicial são mantidos. As betas anteriores à 0.7.0 usaram chaves de teste diferentes e exigem migração única.

Na 0.8.0 ou posterior, abra **Central → Atualizar TCL Light → Verificar atualizações**. Quando houver uma versão nova, escolha Baixar e depois Instalar. O Android pode pedir que você permita instalações pelo TCL Light; a confirmação final continua sendo feita no instalador da TV. O download é manual, pelo GitHub. Com a Home visível, o launcher consulta somente os metadados de atualização, após a abertura e depois respeitando um intervalo de seis horas; falhas permitem tentar novamente após 15 minutos. Não há serviço permanente, download nem instalação automática. Um ícone aparece na barra superior quando existe uma versão compatível mais recente. A última versão anunciada fica salva para o aviso sobreviver ao fechamento do aplicativo.

O atualizador só aceita o pacote do TCL Light, um código de versão maior e o certificado permanente. Confere tamanho, SHA-256, identidade e assinatura do APK em uma tarefa de fundo e confere novamente antes de abrir o instalador. O arquivo fica no cache privado e é compartilhado por uma URI temporária somente de leitura. Falhas e cancelamentos descartam o download parcial.

O canal aponta para [TCL-Light-atualizacao.json](https://github.com/emanoelb5-lgtm/Tvtcl/releases/download/beta/TCL-Light-atualizacao.json); cada manifesto aponta para um APK versionado da mesma página de Releases. O manifesto é gerado a partir dos bytes assinados e validado pelo workflow antes de ser publicado.

As próximas versões devem manter o pacote `com.b5solucoes.tcllight`, a mesma chave privada e um código de versão maior. Mantenha o launcher original da TV instalado para permitir restauração. O aplicativo não desativa componentes do sistema.

O certificado público fica em [signing/tcl-light-release.pem](signing/tcl-light-release.pem); a impressão SHA-256 é registrada em [signing/certificate.sha256](signing/certificate.sha256). A chave privada é mantida pelo proprietário em backup privado, fora do repositório e dos caches do GitHub.

## Uso pelo controle

- OK abre um aplicativo.
- Segurar OK abre opções e ações de mover à esquerda/direita.
- Durante a reorganização, esquerda/direita movem, OK salva e Voltar cancela.
- As posições são salvas separadamente em Mais utilizados e Seus aplicativos. Após a primeira organização manual, a ordem escolhida tem prioridade; o menu permite restaurar a ordenação automática.
- MENU abre e fecha a Central; Voltar retorna um nível.
- A Play Store aparece entre os aplicativos quando instalada e habilitada, inclusive como app de sistema, e tem atalho na Central.
- A faixa superior fica fora da área de rolagem, com recorte dos cartões para que nunca a cubram. Ela reúne clima de Garrafão do Norte, hora, data, rede, entradas e Central. Não há botão de marca nem nomes repetidos sob os cartões.

## Carregamento na 0.13.0

- A Home lê um snapshot local da lista e reconcilia instalações, remoções, componentes e versões em segundo plano. O catálogo tem prioridade sobre a consulta das entradas do firmware.
- Mais utilizados e Seus aplicativos compartilham o mesmo cache de ícones. As imagens são salvas no tamanho dos cartões, com chave por componente, versão e configuração de recursos.
- Continuar assistindo, Minha lista e a busca compartilham as capas e os downloads em andamento. Um worker de disco separado permite reaproveitar capas locais mesmo quando downloads estão lentos.
- Os cartões visíveis são priorizados; o pré-carregamento se limita aos vizinhos imediatos. A ordem, a apresentação e o progresso de Continuar assistindo são preservados.
- Sair da Home não apaga os caches. Pressão real de memória reduz ou libera a RAM; as cópias locais continuam disponíveis. Nenhum serviço de cache permanece rodando em segundo plano.
- Limites: ícones 2 MiB de RAM em aparelhos de pouca memória, 4 MiB nos demais; capas compartilhadas 2/3 MiB. Disco: até 8 MiB de ícones + 16 MiB de capas, 128 arquivos por grupo. Downloads continuam limitados a 1 MiB e decodificação amostrada; só thumbnails são persistidos no cache privado, sem backup.
- As capas HTTPS têm validade de até sete dias, e as de provedores locais, uma hora. A primeira capa ainda depende do serviço que a fornece; depois pode ser reutilizada sem outro download. Limpar o cache do Android remove essas cópias descartáveis.

## Descoberta e navegação na 0.12.0

- TV e canais passa a um ícone pequeno no topo; seus atalhos HDMI personalizados continuam na Home.
- Lupa na barra superior, teclado próprio para controle e resultados em cartões, com disponibilidade no Brasil, tipo de oferta e abertura do título no serviço.
- Aviso de nova versão na barra superior, consulta automática leve e download/instalação mediante escolha do usuário.
- Galerias de cartões na Multimídia e Minha lista. A faixa Continuar assistindo mantém seu formato e funcionamento.

## Interface na 0.11.0

- Home mais compacta, com cabeçalho fixo, clima em destaque, relógio maior e atalhos de TV e multimídia menores. A grade começa mais acima.
- Três temas em **Central → Personalizar aparência → Tema de cores**: Aurora (verde), Safira (azul) e Âmbar (champanhe). A escolha entra no backup; backups anteriores continuam aceitos.
- Painéis com ícones em destaque, títulos e separadores mais claros, cartões e estados de foco consistentes. Os aplicativos continuam sem rótulos repetidos abaixo dos ícones.
- Capas de mídia em 16:9 com identificação e progresso real quando disponibilizado pelo aplicativo de origem.
- Gradientes nativos e animação curta apenas em cartões. Sem novas dependências de execução, fontes baixadas ou efeitos de desfoque. O modo leve remove as animações.

## Multimídia na 0.10.0

Abra **Central → Multimídia e programação** para a galeria de cartões. A lupa na barra superior abre a busca. A Home exibe os cartões reais de Continuar assistindo e Minha lista quando há conteúdo, sem a antiga fileira de botões.

- **Minha lista:** títulos, filmes, séries, canais e links HTTPS, com aplicativo escolhido, nomes próprios, ordenação e remoção pelo controle. Limite de 80 itens/64 KiB. Entra no backup local e continua disponível sem internet. Não importa contas ou históricos privados dos streamings.
- **Busca de conteúdo:** teclado virtual próprio, operado pelo controle, com letras, números, espaço, apagar, limpar e voz opcional. A pesquisa é enviada ao confirmar Buscar. A página pública do JustWatch Brasil informa títulos, ano, serviços e links diretos de conteúdo; o launcher separa assinatura, grátis com anúncios, grátis, aluguel e compra. A escolha do serviço abre o link do título no aplicativo quando compatível ou pelo manipulador HTTPS do Android. Planos, contas e reprodução continuam a cargo do serviço. O detalhe de cada título oferece salvar na Minha lista e o link de atribuição JustWatch.
- **Consulta leve:** uma tarefa cancelável, limite de 2 MiB por página descomprimida, quatro buscas em memória por 15 minutos e capas carregadas apenas quando visíveis. Não há conta JustWatch, chave de parceiro, execução de JavaScript, catálogo sincronizado em segundo plano ou histórico de pesquisas em disco. Alterações ou indisponibilidade da página pública podem interromper a consulta; a interface oferece tentar novamente, abrir JustWatch ou pesquisar dentro de um aplicativo. Essa alternativa encaminha `ACTION_SEARCH` quando disponível, ou permite copiar o título e abrir o app. As buscas locais na lista e Watch Next acessível também continuam disponíveis.
- **Programação:** após autorização de acesso à programação nos ajustes do Android, consulta os canais visíveis e programas das próximas 24 horas no `TvProvider`. Mostra Agora/a seguir, favoritos e opção de assistir pelo app de TV. Canais online, inclusive a numeração a partir de 105, só aparecem se o firmware os publicar. O launcher não solicita permissões de assinatura nem presume acesso a todas as grades. Se o fabricante limitar a consulta, oferece o guia nativo e lembretes manuais.
- **Lembretes:** criação por programa futuro, título da lista ou cadastro manual com data/hora. Aviso agendado até cinco minutos antes, com abertura das opções mediante toque; nunca troca o canal automaticamente. Limite de 32. Reagenda após reiniciar, ajustar o relógio ou atualizar o APK. O Android pode atrasar ou ocultar notificações em espera; a lista continua disponível. Os agendamentos ficam nesta TV e não são recriados ao importar backup em outra.
- **Continuar assistindo:** com o acesso à programação autorizado, lê os itens Watch Next que os aplicativos e o firmware disponibilizam, com capa, progresso, abertura no app de origem e salvamento na Minha lista. Não inventa histórico e não garante compatibilidade universal. Uma mensagem explica quando o Android não fornece acesso.
- **Reproduzindo agora:** mostra título e controles de pausar/continuar para sessões ativas compatíveis. Inicialmente desativado. O acesso às notificações solicitado pelo Android precisa de autorização do usuário; o serviço não lê nem armazena conteúdo de notificações. Os controladores são observados somente enquanto Home está visível. Pode ser desativado pelo próprio painel.
- **Desempenho:** faixas opcionais, consultas canceláveis fora da interface, observação de alterações e capas 16:9 carregadas apenas quando visíveis, com imagens limitadas a 1 MiB e cache de 2 MiB. Sem trailers automáticos ou sincronização periódica de catálogos.

As funções de entradas, rede, margens, backup e vídeo experimental da 0.9.0 continuam disponíveis.

## Recursos da 0.9.0

- **TV e entradas na Home:** a TV nativa começa fixada. Em Central → Atalhos de TV e entradas, fixe as entradas expostas pelo firmware; segure OK no atalho para renomear, mover ou remover. Entradas que deixam de ser disponibilizadas não ficam como botões sem destino.
- **Rede:** abra o botão de rede para consultar conexão, validação de internet e IP da TV. O IP pode ser copiado. A leitura usa callbacks do Android e não dispara testes de velocidade ou varreduras periódicas. “Internet não confirmada” é diferente de afirmar que toda a internet está bloqueada.
- **Área visível e textos:** Personalizar → Área visível e textos permite ajustar margens laterais, superior/inferior e letras de 100%, 110% ou 120%. O fundo preenche a tela; controles, cabeçalho e painéis respeitam a área segura e a rolagem mantém o cartão em foco inteiro.
- **Backup local:** a Central oferece cópia nesta TV, exportação e importação JSON. Antes de restaurar, apresenta uma revisão e exige confirmação. Preserva organização, ocultos, fixados, contagens locais de uso e aparência. A importação tem limite de 256 KiB, tipos/valores limitados e uma lista explícita de preferências permitidas; não restaura destinos de atualização, permissões ou componentes do sistema. A cópia interna sobrevive às atualizações, mas exporte para guardar fora do aplicativo.
- **TV ao fundo (experimental):** teste opcional em Personalizar, inicialmente desligado e sem áudio, apenas para entradas de vídeo conectadas expostas por `TvInputManager` (como HDMI). Usa `TvView`, libera a sessão ao sair do launcher e retorna ao papel de parede em caso de recusa, perda do vídeo, falta de memória ou timeout. Não inicia no modo leve e não é reativado pela restauração de um backup. Canais de antena e a compatibilidade da entrada no aparelho TCL dependem do acesso do firmware; não há reprodução garantida de fontes protegidas nem acesso forçado a canais.

## Entradas e TV

As fontes são consultadas com `TvInputManager`: entradas HDMI, vídeo e sintonizadores disponibilizados pelo firmware. Entradas ocultadas nos ajustes são respeitadas. Teclados, mouses e nomes de dispositivos de entrada não aparecem nessa lista. A troca usa URIs do `TvContract` e oferece acesso ao seletor nativo quando o firmware não expõe a fonte.

TV e canais abre o aplicativo nativo de televisão. O teste de vídeo ao fundo é independente da abertura da TV nativa e permanece desativado até uma seleção explícita do usuário.

## Desempenho e clima

Views nativas, ícones vetoriais, imagens WebP 16:9 offline, decodificação fora da interface e cache limitado. A grade é reaproveitada ao retornar de outro app. Instalações, rede e fontes de vídeo usam eventos; o launcher não varre os aplicativos a cada poucos segundos. O modo leve remove animações e mantém o fundo fixo.

O clima usa HTTPS/Open-Meteo, coordenadas fixas da sede de Garrafão do Norte (-1.93, -47.05), cache de 45 minutos, limite de tamanho e timeout. Não pede localização. Ao ficar sem internet, os aplicativos e o launcher continuam funcionando; dados antigos são identificados ou substituídos por “Clima indisponível”.

Fonte das coordenadas: [IBGE, mapa municipal](https://geoftp.ibge.gov.br/cartas_e_mapas/mapas_municipais/colecao_de_mapas_municipais/2020/PA/garrafao_do_norte/1503077_MM.pdf). Clima: [Open-Meteo, CC BY 4.0](https://open-meteo.com/).

## Compilação e publicação

1. O workflow compila e testa o launcher em Android TV API 28, 1280×720.
2. Gera APK release unsigned e um APK interno com código maior para testar a atualização; nenhum APK debug é distribuído.
3. O proprietário assina com a chave permanente usando [tools/sign_release.py](tools/sign_release.py), em ambiente privado.
4. A branch `signed-releases` contém somente material público de distribuição e o fonte; não contém a chave ou suas senhas.
5. O workflow valida pacote, versões, hashes, metadados de atualização e certificado fixo. No emulador, instala a 0.12.0 pública, altera uma preferência pela interface e aplica a 0.13.0 com `adb install -r`. Depois aplica um APK interno de código maior. A publicação exige que as duas atualizações preservem o Home alternativo e a preferência.

Depois da publicação, outra instalação limpa da 0.12.0 usa o próprio atualizador para consultar o canal beta, baixar a 0.13.0 e confirmar a instalação no instalador Android. A escolha de Home e a preferência salva também são verificadas nesse caminho.

O APK interno de teste não é publicado em Releases. O resultado da verificação acompanha o APK público. O teste reproduz o caso em que a imagem Android TV dá prioridade ao Google: desativa o launcher original apenas para o usuário do emulador descartável, confirma o TCL Light como Home e restaura o componente ao terminar. Os testes do atualizador usam o APK público 0.7.0 de assinatura permanente para validar downloads, integridade e identidade; ele só entra no APK de testes. Esses testes não substituem a validação do instalador no firmware da TCL física.

Veja [signing/README.md](signing/README.md) para preservar a assinatura nas próximas versões.
