# TCL Light 0.7.0

Launcher nativo em Kotlin para Android TV, com foco em controle remoto e TVs com pouca memória.

[Baixar TCL-Light-0.7.0.apk](https://github.com/emanoelb5-lgtm/Tvtcl/releases/download/v0.7.0/TCL-Light-0.7.0.apk) · [Versões](https://github.com/emanoelb5-lgtm/Tvtcl/releases)

## Instalação e atualizações

A versão 0.7.0 inicia a assinatura permanente. As betas antigas usaram certificados de teste diferentes. Desinstale a beta antiga uma única vez, instale a 0.7.0 e escolha novamente a tela inicial. Isso apaga as preferências locais da beta anterior. Mantenha o launcher original da TV instalado.

As próximas versões devem manter o pacote `com.b5solucoes.tcllight`, a mesma chave privada e um código de versão maior. Elas poderão ser instaladas sobre esta versão.

O certificado público fica em [signing/tcl-light-release.pem](signing/tcl-light-release.pem); a impressão SHA-256 é registrada em [signing/certificate.sha256](signing/certificate.sha256). A chave privada é mantida pelo proprietário em backup privado, fora do repositório e dos caches do GitHub.

## Uso pelo controle

- OK abre um aplicativo.
- Segurar OK abre opções e ações de mover à esquerda/direita.
- Durante a reorganização, esquerda/direita movem, OK salva e Voltar cancela.
- As posições são salvas separadamente em Mais utilizados e Seus aplicativos. Após a primeira organização manual, a ordem escolhida tem prioridade; o menu permite restaurar a ordenação automática.
- MENU abre e fecha a Central; Voltar retorna um nível.
- A faixa superior reúne clima de Garrafão do Norte, hora, data, rede, entradas e Central. Não há botão de marca nem nomes repetidos sob os cartões.

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
5. O workflow valida pacote, versões, hashes e certificado fixo. No emulador, instala 0.7.0, altera uma preferência pela interface e aplica a atualização interna com `adb install -r`. A publicação exige que a atualização seja aceita, a preferência seja preservada e a resolução de Home permaneça estável.

O APK interno de teste não é publicado em Releases. O resultado da verificação acompanha o APK público. A imagem Android TV de teste pode impor o launcher original mesmo após aceitar o comando de mudança. Nesse caso, o teste abre o TCL Light diretamente e registra que a seleção de um Home alternativo não foi validada. O launcher original nunca é desativado. Esses testes não substituem a validação no aparelho TCL físico.

Veja [signing/README.md](signing/README.md) para preservar a assinatura nas próximas versões.
