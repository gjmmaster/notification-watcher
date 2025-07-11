# Documentação Técnica Detalhada: Serviço `notification-watcher`

**ID do Documento:** DOC-NW-CORE-20231028-v1.1 (Substituir 20231028 pela data da efetivação)
**Data:** 28 de Outubro de 2023 (Substituir pela data da efetivação)
**Versão:** 1.1 (Reflete o estado após as correções de Outubro de 2023 e detalhamento da implementação de múltiplos WABA IDs e endpoint de API.)
**Autores:** Jules (AI Assistant), Contribuidores do Projeto

## 1. Propósito

Este documento fornece uma descrição técnica detalhada do estado atual de implementação do microsserviço `notification-watcher`, com foco no seu arquivo principal `core.clj` (`notification-watcher/src/notification_watcher/core.clj`). O objetivo é servir como referência para desenvolvedores, detalhando a arquitetura dos componentes, fluxos de dados, configuração, e lógica de negócio implementada. Ele substitui a visão de "status de tarefas pendentes" de versões anteriores deste documento por uma documentação do sistema como ele opera atualmente.

## 2. Visão Geral do Serviço

O `notification-watcher` é um serviço em Clojure desenhado para:

1.  **Obter WABA IDs:** Adquirir uma lista de WhatsApp Business Account IDs (WABA IDs) a serem monitorados. Esta lista pode ser obtida de um serviço externo configurável (o `customer-manager-service`) ou definida localmente através de variáveis de ambiente para fins de teste e desenvolvimento.
2.  **Monitorar Templates da Gupshup:** Para cada WABA ID identificado, o serviço consulta periodicamente a API da Gupshup para buscar a lista de templates de mensagens associados. Existe um modo mock para simular essa interação sem chamadas reais à API.
3.  **Detectar Mudanças de Categoria e Filtrar:** Analisar os templates recebidos para:
    *   Identificar aqueles que tiveram sua categoria alterada (comparando a categoria atual com uma categoria anterior registrada no próprio template).
    *   Filtrar (ignorar) templates cujo status seja "FAILED".
4.  **Registrar Atividades:** Logar no console as mudanças de categoria detectadas e outros eventos importantes da operação do serviço.
5.  **Expor API de Templates Alterados:** Disponibilizar um endpoint HTTP (`GET /changed-templates`) que outros sistemas (como um serviço `sms-notifier`) possam consumir para obter a lista mais recente de templates que tiveram suas categorias alteradas.

## 3. Arquitetura Detalhada e Componentes do `core.clj`

Esta seção detalha os principais componentes e a lógica contida em `notification-watcher/src/notification_watcher/core.clj`.

### 3.1. Configuração e Variáveis de Ambiente

O comportamento do serviço é fundamentalmente controlado por variáveis de ambiente, permitindo flexibilidade na configuração para diferentes ambientes (desenvolvimento, teste, produção).

*   **`GUPSHUP_TOKEN`** (String)
    *   **Propósito:** Token de API essencial para autenticar as requisições feitas à API da Gupshup.
    *   **Uso no Código:** Lida pela função `check-all-waba-ids-for-changes!` e passada para `fetch-templates-for-waba`, que a inclui no header `:apikey` das chamadas HTTP GET.
    *   **Obrigatoriedade:** Obrigatória. A ausência ou um valor em branco impede a busca de templates da Gupshup (resultando em erro logado e nenhuma busca de template).
*   **`CUSTOMER_MANAGER_URL`** (String)
    *   **Propósito:** URL base do `customer-manager-service`. Este serviço externo é a fonte primária para a lista de WABA IDs ativos que devem ser monitorados.
    *   **Uso no Código:** Utilizada pela função `fetch-waba-ids`. Se presente, `fetch-waba-ids` constrói a URL completa (ex: `<CUSTOMER_MANAGER_URL>/internal/customers/active-waba-ids`) e realiza uma chamada HTTP GET para obter os WABA IDs.
    *   **Obrigatoriedade:** Opcional. Se não fornecida, o sistema recorre a `MOCK_CUSTOMER_MANAGER_WABA_IDS`. Se ambas estiverem ausentes, nenhum WABA ID será processado.
*   **`MOCK_CUSTOMER_MANAGER_WABA_IDS`** (String)
    *   **Propósito:** String contendo WABA IDs separados por vírgula, usada primariamente para desenvolvimento e testes, permitindo ao sistema operar com um conjunto definido de WABA IDs sem depender do `customer-manager-service`.
    *   **Uso no Código:** Utilizada em `fetch-waba-ids` como fallback se `CUSTOMER_MANAGER_URL` não estiver definida. A string é parseada para uma lista de WABA IDs.
    *   **Obrigatoriedade:** Opcional.
    *   **Formato Exemplo:** `"waba_id_1,waba_id_2,waba_id_3"`
*   **`GUPSHUP_MOCK_MODE`** (String)
    *   **Propósito:** Flag para ativar/desativar o modo de mock para interações com a API da Gupshup. Quando ativo, previne chamadas HTTP reais à Gupshup.
    *   **Uso no Código:** Verificada em `fetch-templates-for-waba`. Se seu valor é a string `"true"`, os templates são recuperados do atom `mock-templates-store` em vez de uma chamada HTTP. Também controla a inicialização do `mock-templates-store` via `initialize-mock-gupshup-store!`.
    *   **Obrigatoriedade:** Opcional. (Padrão implícito é `false`, ou seja, modo real).
*   **`PORT`** (String)
    *   **Propósito:** Especifica a porta TCP na qual o servidor web integrado (http-kit) irá escutar por requisições HTTP.
    *   **Uso no Código:** Lida na função `-main` e passada para `server/run-server`.
    *   **Obrigatoriedade:** Opcional.
    *   **Padrão:** `"8080"` se a variável não estiver definida.

### 3.2. Estado Global e Gerenciamento de Dados Mockados

O serviço utiliza `atom`s do Clojure para gerenciar estado mutável de forma segura e concorrente.

*   **`mock-templates-store`** (`clojure.lang.Atom`)
    *   **Definição:** `(atom {})`
    *   **Propósito:** Armazena os dados de template mockados quando `GUPSHUP_MOCK_MODE` está ativo. É um mapa Clojure onde as chaves são strings de WABA ID e os valores são vetores de mapas de template.
    *   **Gerenciamento:**
        *   **`initialize-mock-gupshup-store!`**: Chamada no início do `start-watcher-loop!`. Se `GUPSHUP_MOCK_MODE` é `"true"`, esta função popula o `mock-templates-store`. Ela determina os WABA IDs a serem mockados a partir de `MOCK_CUSTOMER_MANAGER_WABA_IDS` (se disponível) ou usa uma lista default (`["mock_waba_id_default1", "mock_waba_id_default2"]`). Para cada WABA ID, chama `generate-mock-templates-for-waba` e associa o resultado ao WABA ID no atom.
        *   **`generate-mock-templates-for-waba [waba-id]`**: Função auxiliar que retorna um vetor de mapas de template. Cada mapa é um template mockado com chaves como keywords (ex: `:id`, `:elementName`, `:wabaId`, `:category`, `:oldCategory`, `:status`, `:language`). A estrutura inclui uma variedade de templates para facilitar o teste das lógicas de filtragem e detecção de mudança (templates com e sem `:oldCategory`, com status `"APPROVED"` e `"FAILED"`).
    *   **Consumo:** Lido por `fetch-templates-for-waba` quando em modo mock.

*   **`changed-templates-atom`** (`clojure.lang.Atom`)
    *   **Definição:** `(atom [])`
    *   **Propósito:** Mantém a lista (vetor) mais recente de todos os templates que foram identificados com mudança de categoria, agregados de todos os WABA IDs processados no último ciclo de verificação bem-sucedido do watcher.
    *   **Atualização:** A função `check-all-waba-ids-for-changes!` é responsável por calcular esta lista. Ao final de sua execução, ela usa `reset!` para substituir completamente o conteúdo do `changed-templates-atom` pela nova lista de templates alterados. Se nenhum template alterado for encontrado, o atom será resetado para um vetor vazio.
    *   **Consumo:** Lido pela função `app-handler` (especificamente para o endpoint `GET /changed-templates`), onde seu conteúdo é serializado para JSON e retornado na resposta HTTP.

### 3.3. Ponto de Entrada e Loop Principal do Watcher

A execução do serviço e o monitoramento contínuo são orquestrados pelas seguintes funções:

*   **`-main [& args]`**
    *   **Propósito:** Ponto de entrada principal da aplicação, conforme definido para execução com Leiningen (`lein run`).
    *   **Funcionalidade:**
        1.  Parseia a variável de ambiente `PORT` (ou usa o padrão "8080").
        2.  Loga as configurações iniciais do serviço (estado do modo mock, URLs, etc.).
        3.  Chama `start-watcher-loop!` para iniciar o processo de monitoramento em background.
        4.  Inicia o servidor HTTP `http-kit` na porta configurada, usando `app-handler` para tratar as requisições.
        5.  Loga a inicialização bem-sucedida do servidor.

*   **`start-watcher-loop!`**
    *   **Propósito:** Configura e inicia o ciclo de monitoramento de templates em uma thread separada.
    *   **Funcionalidade:**
        1.  Chama `initialize-mock-gupshup-store!` para popular os dados de mock, se aplicável.
        2.  Verifica a presença do `GUPSHUP_TOKEN`. Se ausente, loga um erro crítico e o watcher não é efetivamente iniciado para chamadas reais.
        3.  Se o token estiver presente (ou em modo mock), lança um novo processo (`future`) que contém o loop de monitoramento. Isso permite que o servidor HTTP continue responsivo enquanto o watcher opera em background.
        4.  **Dentro do `future`:**
            *   Aguarda um atraso inicial de 30 segundos (`Thread/sleep 30000`) antes da primeira verificação.
            *   Entra em um `loop` infinito:
                *   Chama `check-all-waba-ids-for-changes!` para executar um ciclo completo de verificação.
                *   Loga a conclusão do ciclo.
                *   Aguarda 10 minutos (`Thread/sleep 600000`) antes de iniciar o próximo ciclo.
                *   Utiliza `try-catch Throwable` para capturar quaisquer exceções inesperadas dentro do loop, logando-as detalhadamente sem interromper o ciclo de watcher (ele tentará novamente após o intervalo).

*   **`check-all-waba-ids-for-changes!`**
    *   **Propósito:** Orquestra um ciclo completo de verificação de templates para todos os WABA IDs configurados.
    *   **Funcionalidade:**
        1.  Verifica novamente a presença do `GUPSHUP_TOKEN`.
        2.  Chama `fetch-waba-ids` para obter a lista de WABA IDs a serem processados.
        3.  Se a lista de WABA IDs estiver vazia, limpa o `changed-templates-atom` (`reset! []`).
        4.  Se houver WABA IDs, itera sobre cada um (`mapcat`) chamando `process-templates-for-waba` para obter os templates alterados para aquele WABA ID.
        5.  Filtra resultados nulos (`filter identity`) e coleta todos os templates alterados de todos os WABA IDs em um único vetor (`vec`).
        6.  Atualiza o `changed-templates-atom` com esta nova lista agregada usando `reset!`.
        7.  Loga o número total de templates alterados encontrados e o status da atualização do atom.

### 3.4. Lógica de Negócio: Obtenção de WABA IDs

*   **`fetch-waba-ids []`**
    *   **Propósito:** Responsável por fornecer a lista de WABA IDs que o watcher deve monitorar.
    *   **Fluxo de Decisão e Execução:**
        1.  **Verifica `MOCK_CUSTOMER_MANAGER_WABA_IDS`:** Se esta variável de ambiente estiver definida e não for uma string em branco, seu valor é dividido por vírgulas (`str/split #","`), os espaços são removidos (`str/trim`), e a lista resultante de strings de WABA ID é retornada. Uma mensagem de log indica o uso desta fonte mock.
        2.  **Verifica `CUSTOMER_MANAGER_URL`:** Se `MOCK_CUSTOMER_MANAGER_WABA_IDS` não for usada e `CUSTOMER_MANAGER_URL` estiver definida, o serviço tenta buscar os WABA IDs do `customer-manager-service`:
            *   Constrói a URL completa: `<CUSTOMER_MANAGER_URL>/internal/customers/active-waba-ids`.
            *   Realiza uma chamada HTTP GET para esta URL usando `clj-http.client/get` com as seguintes configurações:
                *   `:as :json`: Espera que a resposta seja JSON e a parseia automaticamente (convertendo chaves JSON para keywords Clojure).
                *   `:throw-exceptions false`: Impede que `clj-http` lance exceções para status HTTP não-2xx, permitindo tratamento manual.
                *   `:conn-timeout 5000` (5 segundos).
                *   `:socket-timeout 5000` (5 segundos).
            *   **Tratamento da Resposta:**
                *   Se o status HTTP for `200`:
                    *   Tenta extrair a lista de IDs de `(:waba_ids (:body response))`.
                    *   Verifica se o resultado é um vetor e se todos os seus elementos são strings. Se sim, retorna esta lista.
                    *   Se a estrutura for inesperada, loga um erro e retorna uma lista vazia.
                *   Se o status HTTP não for `200`, loga um erro com o status e o corpo da resposta (se houver) e retorna uma lista vazia.
            *   **Tratamento de Exceção:** Se ocorrer qualquer exceção durante a chamada HTTP (ex: `java.net.ConnectException`), a exceção é capturada, logada (incluindo stack trace), e uma lista vazia é retornada.
        3.  **Nenhuma Fonte Definida:** Se nem `MOCK_CUSTOMER_MANAGER_WABA_IDS` nem `CUSTOMER_MANAGER_URL` estiverem definidas, loga um erro e retorna uma lista vazia.
    *   **Retorno:** Um vetor de strings, onde cada string é um WABA ID. Pode ser um vetor vazio se nenhuma fonte válida for encontrada ou se ocorrerem erros.

### 3.5. Lógica de Negócio: Busca de Templates da Gupshup

*   **`fetch-templates-for-waba [waba-id token]`**
    *   **Propósito:** Busca a lista de templates para um WABA ID específico, seja da API real da Gupshup ou do mock store.
    *   **Parâmetros:**
        *   `waba-id` (String): O WABA ID para o qual buscar os templates.
        *   `token` (String): O `GUPSHUP_TOKEN` para autenticação.
    *   **Fluxo de Decisão e Execução:**
        1.  **Modo Mock (`GUPSHUP_MOCK_MODE` é `"true"`):**
            *   Loga que está usando dados mockados para o `waba-id`.
            *   Recupera a lista de templates do atom `@mock-templates-store` usando o `waba-id` como chave. Se não houver entrada, retorna uma lista vazia `[]`.
            *   Loga o número de templates mockados encontrados.
        2.  **Modo Real (API da Gupshup):**
            *   Constrói a URL da API: `https://api.gupshup.io/sm/api/v1/template/list/<waba-id>`.
            *   Loga a tentativa de conexão.
            *   Realiza uma chamada HTTP GET usando `clj-http.client/get` com:
                *   `headers`: `{ :apikey token }`
                *   `:as :json`: Para parsear a resposta JSON. Chaves JSON são convertidas para keywords Clojure.
                *   `:throw-exceptions false`: Para tratamento manual de erros HTTP.
                *   `:conn-timeout 60000` (60 segundos).
                *   `:socket-timeout 60000` (60 segundos).
            *   **Tratamento da Resposta da API:**
                *   Loga o status HTTP recebido.
                *   **Erro Crítico de HTTP (`:error` preenchido por `clj-http`):** Se `(:error response)` existir (ex: timeout antes de obter status), loga o erro e sua causa (se disponível), e retorna `nil`.
                *   **Status HTTP não é 200:** Loga o erro, o status HTTP e o corpo da resposta (usando `pprint/pprint`), e retorna `nil`.
                *   **Corpo da Resposta não é um Mapa (mesmo com status 200):** Se `(:body response)` não for um mapa após o parsing JSON (indicando uma resposta inesperada), loga um alerta, o corpo da resposta, e retorna `nil`.
                *   **Sucesso (Status 200 e corpo é um mapa):**
                    *   Extrai a lista de templates de `(get-in (:body response) [:templates])`.
                    *   Loga o número de templates recebidos.
                    *   (Há um bloco de código comentado `#_ (when ...)` para debug da estrutura do primeiro template, se necessário).
                    *   Retorna a lista de templates extraída, ou `[]` se a chave `:templates` não existir ou for nula.
            *   **Tratamento de Exceção Geral:** Se qualquer outra exceção ocorrer durante a chamada ou processamento inicial da resposta, ela é capturada (`catch Exception e`), logada detalhadamente (tipo, mensagem, stack trace), e a função retorna `nil`.
    *   **Retorno:** Uma lista de mapas de template (se bem-sucedido), `nil` (em caso de erro na chamada HTTP ou parsing), ou uma lista vazia (se nenhuma template for encontrado ou no modo mock sem dados para o WABA ID).

### 3.6. Lógica de Negócio: Processamento de Templates e Detecção de Mudanças

*   **`process-templates-for-waba [waba-id token]`**
    *   **Propósito:** Orquestra a busca e o processamento de templates para um WABA ID específico, identificando e logando aqueles com mudança de categoria.
    *   **Parâmetros:**
        *   `waba-id` (String): O WABA ID sendo processado.
        *   `token` (String): O `GUPSHUP_TOKEN`.
    *   **Fluxo de Execução:**
        1.  Loga o início do processamento para o `waba-id`.
        2.  Chama `fetch-templates-for-waba` para obter todos os templates (`all-templates`).
        3.  **Se `fetch-templates-for-waba` retorna `nil` ou uma lista vazia:**
            *   Loga que não foi possível obter templates ou que a lista está vazia.
            *   Retorna uma lista vazia `[]` (indicando nenhum template alterado).
        4.  **Se templates foram recebidos:**
            *   Calcula `total-received` (contagem de `all-templates`).
            *   `map-templates`: Filtra `all-templates` para manter apenas os elementos que são mapas (`filter map?`).
            *   `active-templates`: Filtra `map-templates` para manter apenas aqueles onde `(:status %)` **não** é igual a `"FAILED"`.
            *   `total-active`: Contagem de `active-templates`.
            *   `changed-category-templates`: Filtra `active-templates` para manter apenas aqueles que contêm a chave `:oldCategory` (`(contains? % :oldCategory)`). Isso indica uma mudança de categoria.
            *   `count-changed`: Contagem de `changed-category-templates`.
            *   **Logging de Debug Detalhado:** Itera sobre `active-templates` e loga, para cada template:
                *   ID (usando `(or (:templateId template) (:id template) "(id missing)")`)
                *   Nome (usando `(or (:templateName template) (:elementName template) "(name missing)")`)
                *   Categoria Atual (usando `(or (:templateCategory template) (:category template) "(category missing)")`)
                *   Se possui `:oldCategory` ou `:previousCategory` (`(or (contains? template :oldCategory) (contains? template :previousCategory))`)
                *   Valor de `:oldCategory` ou `:previousCategory` (`(or (:oldCategory template) (:previousCategory template) "(old/previous category not present)")`)
            *   Loga um sumário para o WABA ID: total recebido, aviso se itens não-mapa foram ignorados, total de ativos.
            *   **Se `count-changed` for positivo:**
                *   Loga o número de templates com mudança de categoria encontrados.
                *   Itera sobre `changed-category-templates`, chamando `log-category-change` para cada um.
                *   **Retorna:** Uma nova lista, onde cada template de `changed-category-templates` é modificado para incluir a chave `"wabaId"` com o valor do `waba-id` atual (`(map #(assoc % "wabaId" waba-id) changed-category-templates)`). *Nota: A chave associada é a string `"wabaId"`, não a keyword `:wabaId`.*
            *   **Se `count-changed` for zero:**
                *   Loga que nenhum template com mudança de categoria foi encontrado.
                *   Retorna uma lista vazia `[]`.
    *   **Retorno:** Uma lista (vetor) contendo apenas os mapas dos templates que tiveram suas categorias alteradas, cada um acrescido da chave `"wabaId"`. Retorna uma lista vazia se nenhum template for alterado ou se ocorrerem erros na obtenção dos templates.

### 3.7. Lógica de Negócio: Logging de Mudanças de Categoria

*   **`log-category-change [template waba-id]`**
    *   **Propósito:** Formata e imprime no console os detalhes de um template que teve sua categoria alterada.
    *   **Parâmetros:**
        *   `template` (Map): O mapa do template que mudou.
        *   `waba-id` (String): O WABA ID ao qual o template pertence.
    *   **Funcionalidade:**
        *   Extrai os seguintes campos do `template` usando acesso por keyword:
            *   `:id` (como `template-id`)
            *   `:elementName`
            *   `:oldCategory`
            *   `:category` (como `newCategory`)
        *   Imprime múltiplas linhas no console (`println`) formatadas para mostrar claramente o WABA ID, ID do template, nome, categoria antiga e nova categoria.

### 3.8. Servidor HTTP e Endpoints

O serviço expõe uma API HTTP mínima para consulta de status e dos templates alterados.

*   **`app-handler [request]`**
    *   **Propósito:** Função principal de tratamento de requisições HTTP, usando `org.httpkit.server`.
    *   **Roteamento:** Utiliza um `case` simples sobre `(:uri request)` para determinar qual handler chamar.
    *   **Endpoints Definidos:**
        *   **`GET /`**:
            *   **Funcionalidade:** Retorna uma resposta de status simples (HTTP 200) com o corpo de texto `"Serviço Notification Watcher está no ar."`.
            *   **Headers:** `{"Content-Type" "text/plain; charset=utf-8"}`.
        *   **`GET /changed-templates`**:
            *   **Funcionalidade:** Retorna a lista de templates com categorias alteradas que estão atualmente armazenados no atom `@changed-templates-atom`.
            *   O conteúdo do atom (um vetor de mapas Clojure) é serializado para uma string JSON usando `clojure.data.json/write-str`.
            *   **Headers:** `{"Content-Type" "application/json; charset=utf-8"}`.
        *   **Outras URIs (Não Mapeadas):**
            *   Retorna uma resposta HTTP 404 com o corpo `"Recurso não encontrado."`.
            *   **Headers:** `{"Content-Type" "text/plain; charset=utf-8"}`.

## 4. Considerações para Evolução Futura

Com base na análise do `core.clj` atual e nas práticas comuns de desenvolvimento de microsserviços, os seguintes pontos representam áreas para futuras melhorias e desenvolvimento:

1.  **Logging Estruturado:**
    *   **Situação Atual:** O serviço utiliza `println` para todos os logs.
    *   **Sugestão:** Implementar uma biblioteca de logging mais robusta e configurável (ex: `clojure.tools.logging` com uma implementação como SLF4J/Logback). Isso permitiria níveis de log, formatação estruturada (ex: JSON), e melhor integração com sistemas de agregação de logs em ambientes de produção.

2.  **Mecanismo de Retentativas (Retry) para Chamadas HTTP:**
    *   **Situação Atual:** Chamadas HTTP para o `customer-manager-service` e a API da Gupshup não possuem lógica de retentativa explícita em caso de falhas transitórias.
    *   **Sugestão:** Incorporar uma biblioteca como `diehard` ou implementar uma lógica customizada para retentar chamadas falhas com uma estratégia de backoff exponencial. Isso aumentaria a resiliência do serviço a problemas temporários de rede ou indisponibilidade momentânea dos serviços externos.

3.  **Testes Abrangentes:**
    *   **Situação Atual:** O arquivo `test/notification_watcher/core_test.clj` existe, mas seu conteúdo e abrangência não foram avaliados no escopo desta documentação.
    *   **Sugestão:** Desenvolver/atualizar testes unitários para as funções de lógica de negócio (ex: `process-templates-for-waba`, `fetch-waba-ids`, `fetch-templates-for-waba` com mocks) e testes de integração para os endpoints HTTP e o fluxo do watcher. Considerar casos de borda e cenários de erro.

4.  **Gerenciamento Avançado de Estado para Detecção de Mudanças:**
    *   **Situação Atual:** A detecção de mudança de categoria depende exclusivamente da presença do campo `:oldCategory` no template fornecido pela API da Gupshup.
    *   **Sugestão (Opcional/Para Maior Robustez):** Se o campo `:oldCategory` não for consistentemente fornecido ou se for necessário detectar outros tipos de mudanças, poderia ser avaliada a implementação de um armazenamento de estado anterior dos templates (ex: em memória com TTL, ou um cache externo). Isso permitiria uma comparação mais detalhada entre o estado atual e o anterior.

5.  **Consistência Interna de Chaves:**
    *   **Situação Atual:** Em `process-templates-for-waba`, ao adicionar o WABA ID ao template alterado, é usada a string `"wabaId"` (`(assoc % "wabaId" waba-id)`), enquanto o restante do código e os dados mock utilizam keywords.
    *   **Sugestão:** Para maior consistência interna, considerar usar `(assoc % :wabaId waba-id)`. Embora `clojure.data.json/write-str` converta todas as chaves para strings no JSON final, a consistência interna com keywords é mais idiomática em Clojure.

6.  **Paralelização (Opcional, para Performance com Muitos WABA IDs):**
    *   **Situação Atual:** A busca de templates para múltiplos WABA IDs ocorre de forma sequencial (devido ao `mapcat` sobre `process-templates-for-waba` que, por sua vez, chama `fetch-templates-for-waba` sequencialmente para cada WABA ID).
    *   **Sugestão:** Se o número de WABA IDs a serem processados se tornar muito grande, e a performance do ciclo de 10 minutos for um problema, pode-se investigar a paralelização da busca de templates (ex: usando `pmap` ou um pool de agentes), com o devido cuidado para não exceder limites de taxa da API Gupshup e para gerenciar a complexidade adicional.

Estas considerações visam aprimorar a robustez, manutenibilidade e escalabilidade do serviço `notification-watcher`.
