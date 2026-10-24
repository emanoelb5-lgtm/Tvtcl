# TCL Light 0.8.0

Launcher nativo em Kotlin para Android TV, com foco em controle remoto e TVs com pouca memória.

[Baixar TCL-Light-0.8.0.apk](https://github.com/emanoelb5-lgtm/Tvtcl/releases/download/v0.8.0/TCL-Light-0.8.0.apk) · [Versões](https://github.com/emanoelb5-lgtm/Tvtcl/releases)

## Instalação e atualizações

Se você já usa a **0.7.0, instale a 0.8.0 por cima**, sem desinstalar. O pacote e a assinatura permanente são os mesmos; a organização, os ajustes e a escolha de tela inicial são mantidos. As betas anteriores à 0.7.0 usaram chaves de teste diferentes e exigem migração única.

Na 0.8.0, abra **Central → Atualizar TCL Light → Verificar atualizações**. Quando houver uma versão nova, escolha Baixar e depois Instalar. O Android pode pedir que você permita instalações pelo TCL Light; a confirmação final continua sendo feita no instalador da TV. O download é manual, pelo GitHub, sem serviço permanente ou consultas periódicas.

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

## Entradas e TV

As fontes são consultadas com `TvInputManager`: entradas HDMI, vídeo e sintonizadores disponibilizados pelo firmware. Entradas ocultadas nos ajustes são respeitadas. Teclados, mouses e nomes de dispositivos de entrada não aparecem nessa lista. A troca usa URIs do `TvContract` e oferece acesso ao seletor nativo quando o firmware não expõe a fonte.

TV e canais abre o aplicativo nativo de televisão. Não há reprodução de TV atrás do launcher nesta versão; o acesso à imagem e ao áudio por terceiros depende do firmware.

## Desempenho e clima

Views nativas, ícones vetoriais, imagens WebP 16:9 offline, decodificação fora da interface e cache limitado. A grade é reaproveitada ao retornar de outro app. Instalações, rede e fontes de vídeo usam eventos; o launcher não varre os aplicativos a cada poucos segundos. O modo leve remove animações e mantém o fundo fixo.

O clima usa HTTPS/Open-Meteo, coordenadas fixas da sede de Garrafão do Norte (-1.93, -47.05), cache de 45 minutos, limite de tamanho e timeout. Não pede localização. Ao ficar sem internet, os aplicativos e o launcher continuam funcionando; dados antigos são identificados ou substituídos por “Clima indisponível”.

Fonte das coordenadas: [IBGE, mapa municipal](https://geoftp.ibge.gov.br/cartas_e_mapas/mapas_municipais/colecao_de_mapas_municipais/2020/PA/garrafao_do_norte/1503077_MM.pdf). Clima: [Open-Meteo, CC BY 4.0](https://open-meteo.com/).

## Compilação e publicação

1. O workflow compila e testa o launcher em Android TV API 28, 1280×720.
2. Gera APK release unsigned e um APK interno com código maior para testar a atualização; nenhum APK debug é distribuído.
3. O proprietário assina com a chave permanente usando [tools/sign_release.py](tools/sign_release.py), em ambiente privado.
4. A branch `signed-releases` contém somente material público de distribuição e o fonte; não contém a chave ou suas senhas.
5. O workflow valida pacote, versões, hashes, metadados de atualização e certificado fixo. No emulador, instala a 0.7.0 pública, altera uma preferência pela interface e aplica a 0.8.0 com `adb install -r`. Depois aplica um APK interno de código maior. A publicação exige que as duas atualizações preservem o Home alternativo e a preferência.

O APK interno de teste não é publicado em Releases. O resultado da verificação acompanha o APK público. O teste reproduz o caso em que a imagem Android TV dá prioridade ao Google: desativa o launcher original apenas para o usuário do emulador descartável, confirma o TCL Light como Home e restaura o componente ao terminar. Os testes do atualizador usam o APK público 0.7.0 de assinatura permanente para validar downloads, integridade e identidade; ele só entra no APK de testes. Esses testes não substituem a validação do instalador no firmware da TCL física.

Veja [signing/README.md](signing/README.md) para preservar a assinatura nas próximas versões.
