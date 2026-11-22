# DocScan Pro para Android

Digitalize documentos com a câmera ou importe imagens da galeria, ajuste as páginas e crie um PDF multipágina. O DocScan Pro é um app Android nativo em Kotlin e Jetpack Compose, baseado no projeto `scanner_pdf`.

## Recursos

- **Digitalização com a câmera.** O Google ML Kit oferece captura automática ou manual, detecção de bordas e correção de perspectiva. O app preserva os ajustes feitos no scanner e mostra uma animação de varredura ao preparar a captura e ao incorporar as páginas.
- **Edição de páginas.** Ajuste o recorte com oito alças, gire a imagem, corrija a inclinação e compare com o original. Escolha entre cinco filtros e controle brilho, contraste e intensidade. Você também pode aplicar o mesmo filtro a todas as páginas.
- **Organização e rascunho.** Adicione até 60 páginas, altere a ordem ou remova uma imagem. O app guarda o rascunho no armazenamento privado e o recupera ao reabrir.
- **Biblioteca.** O início mostra os documentos recentes com miniatura, nome, data e número de páginas. Busque por nome, reabra documentos para editar ou selecione vários para compartilhar ou excluir.
- **PDF e compartilhamento.** Escolha papel A4, Carta ou tamanho da imagem, além de orientação, margens e qualidade. O botão **Compartilhar** gera o PDF direto do rascunho e mostra atalhos para WhatsApp, e-mail e Google Drive quando disponíveis. **Outros aplicativos** abre o seletor do Android.

## Como usar

1. Toque em **Digitalizar com a câmera** ou **Importar imagens**. As imagens da galeria recebem enquadramento automático quando há bordas confiáveis e começam com o filtro Cor mágica.
2. Selecione as páginas pelas miniaturas. Use **Recortar**, **Girar**, **Antes** ou **Ajustes** para revisar o documento.
3. No menu **Opções do documento → Opções do PDF**, ajuste o nome e o formato. Toque em **Salvar** para guardar o documento na biblioteca. Use **Exportar cópia em PDF** no mesmo menu para escolher um destino no aparelho.
4. Na biblioteca, toque em um item para ver **Ver** e **Compartilhar**. Abra o menu do item para renomear ou excluir. Use as caixas de seleção ou pressione um item para ações em lote.

A captura com o ML Kit requer Google Play Services. O módulo do scanner pode ser baixado no primeiro uso. A edição, o realce Tiny ML e a geração do PDF rodam no aparelho. [Documentação do ML Kit](https://developers.google.com/ml-kit/vision/doc-scanner/android)

## Compilar e instalar

- JDK 17 ou 21.
- Android SDK Platform 35, configurado no Android Studio ou pela variável `ANDROID_HOME`.
- Dispositivo ou emulador com Android 7.0 (API 24) ou superior para instalar e executar.

Clone o projeto e compile com o Gradle Wrapper incluído.

```bash
git clone https://github.com/laurobrizidio/docscan-pro-android.git
cd docscan-pro-android
./gradlew :app:assembleDebug
```

O APK de depuração é gerado em `app/build/outputs/apk/debug/app-debug.apk`. O comando abaixo instala o app em um dispositivo conectado por ADB ou em um emulador.

```bash
./gradlew :app:installDebug
```

No Android Studio, abra a raiz do projeto e execute a configuração `app`. No Windows, use `gradlew.bat` no lugar de `./gradlew`.

## Arquitetura

O projeto usa Clean Architecture e MVVM. A interface observa o estado do `ScanViewModel` com `StateFlow` e envia ações ao ViewModel. A `MainActivity` fornece a implementação local do repositório. A leitura, o processamento de imagens e a geração de PDFs rodam em corrotinas fora da thread principal.

| Camada | Responsabilidade |
| --- | --- |
| [`domain`](domain/src/main/kotlin/br/com/docscan/domain) | Módulo Kotlin/JVM independente do Android com modelos, regras de importação e paginação e o contrato do repositório. |
| [`data`](app/src/main/java/br/com/docscan/data) | Metadados da biblioteca no Room, páginas e PDFs em armazenamento privado, rascunho local, detecção de bordas, filtros e inferência Tiny ML. |
| [`ui`](app/src/main/java/br/com/docscan/ui) | Telas Compose, ViewModel, integração com o scanner, seletores do Android e compartilhamento. |

A prévia processa até 1100 px no lado maior. A exportação usa até 2400 px. O Tiny ML reutiliza os pesos treinados no projeto original para distinguir tinta e papel.

## Validar mudanças

Execute os testes do domínio, o lint e a compilação em um comando.

```bash
./gradlew :domain:test :app:lintDebug :app:assembleDebug
```

Para verificar o fluxo no aparelho, salve dois documentos, reabra o app e confira a ordem de Recentes. Busque, renomeie, reabra um documento no editor e compartilhe os dois PDFs. O [AGENTS.md](AGENTS.md) indica os pontos de entrada do código e o fluxo de validação.
