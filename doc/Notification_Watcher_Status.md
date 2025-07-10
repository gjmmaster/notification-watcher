# Documentação Técnica: Status do Serviço `notification-watcher`

**ID do Documento:** DOC-NW-STATUS-20231027-v1.0
**Data:** 27 de Outubro de 2023
**Versão:** 1.0
**Autor:** Jules (AI Assistant)

## 1. Propósito

Este documento detalha o estado atual de desenvolvimento do microsserviço `notification-watcher`. Ele descreve as funcionalidades já implementadas e as tarefas pendentes necessárias para que o serviço seja considerado completo de acordo com os requisitos definidos no documento `doc/SNCT_Relatorio_Tecnico_Onboarding_v3.md`.

## 2. Visão Geral do Serviço

O `notification-watcher` é responsável por:
1.  Obter a lista de clientes/aplicativos (WABA IDs) a serem monitorados a partir do `customer-manager-service`.
2.  Consultar a API do provedor (Gupshup) para cada cliente para buscar o status atual dos templates de mensagens.
3.  Detectar mudanças de categoria nesses templates.
4.  Expor um endpoint (`/changed-templates`) que o `sms-notifier` possa consumir para obter a lista de templates com categorias alteradas.

## 3. Funcionalidades Implementadas

Conforme a análise do código em `notification-watcher/src/notification_watcher/core.clj` e `project.clj`:

*   **Lógica Principal de Detecção (Versão Inicial):**
    *   Capacidade de buscar templates da API da Gupshup (usando `clj-http`).
    *   Suporte a um modo de mock (`MOCK_MODE`) para retornar dados de teste sem chamar a API externa.
    *   Identificação de templates com mudança de categoria através da verificação da existência da chave `:oldCategory` na resposta da API.
    *   Log das mudanças detectadas no console (`println`).
    *   Filtra e processa apenas templates que não possuem o status `"FAILED"`.
*   **Servidor HTTP Básico:**
    *   Um servidor HTTP (`http-kit`) está implementado.
    *   Expõe um endpoint raiz (`GET /`) que retorna uma mensagem simples, primariamente usado para "keep-alive" em plataformas como Render.
*   **Worker em Background:**
    *   Um loop de verificação roda em uma thread separada (`future`).
    *   Este loop executa a lógica de `check-for-changes` a cada 10 minutos, após um atraso inicial de 30 segundos.
*   **Configuração:**
    *   O serviço é configurável via variáveis de ambiente:
        *   `GUPSHUP_APP_ID`: ID da aplicação Gupshup (atualmente usado de forma singular).
        *   `GUPSHUP_TOKEN`: Token da API Gupshup.
        *   `MOCK_MODE`: Para ativar/desativar o uso de dados mockados.
        *   `PORT`: Porta para o servidor HTTP.
    *   Timeouts para chamadas HTTP via `clj-http` estão configurados (:conn-timeout e :socket-timeout em 60000ms).
*   **Tratamento de Erros (Básico):**
    *   Blocos `try-catch` em volta das chamadas à API Gupshup e no loop principal do worker para capturar e logar exceções no console.
*   **Build e Documentação Inicial:**
    *   `project.clj` configurado para build com Leiningen (incluindo perfil `:uberjar`).
    *   `README.md` com instruções básicas de configuração e execução.
    *   `doc/intro.md` com um resumo do projeto, arquitetura e decisões de design (refletindo um estado anterior, mas útil para contexto).
    *   `Dockerfile` (mencionado em `doc/intro.md`) para builds multi-stage em container.

## 4. Tarefas Pendentes para Finalização

Para que o `notification-watcher` esteja alinhado com a arquitetura definida em `doc/SNCT_Relatorio_Tecnico_Onboarding_v3.md` e pronto para integração com os demais serviços, as seguintes tarefas precisam ser concluídas:

1.  **Integração com `customer-manager-service`:**
    *   **Modificar `fetch-templates` e `check-for-changes`:**
        *   Remover a dependência direta da variável de ambiente `GUPSHUP_APP_ID` para buscar um único App ID.
        *   Implementar a lógica para chamar o endpoint `/internal/customers/active-waba-ids` do `customer-manager-service` para obter a lista de WABA IDs ativos.
        *   Iterar sobre cada WABA ID recebido, chamando a API da Gupshup (ou usando a lógica de mock) para cada um.
        *   A API Key da Gupshup (`GUPSHUP_TOKEN`) pode continuar sendo global, assumindo que é a mesma para todos os WABA IDs gerenciados, ou adaptar para um modelo onde a chave também possa vir do `customer-manager-service` se necessário.
2.  **Implementação do Endpoint `/changed-templates`:**
    *   **Criar/Modificar `app-handler` (ou rotas em `reitit`):**
        *   Definir uma rota `GET /changed-templates`.
        *   Este endpoint deve retornar uma lista (em formato JSON) dos templates que foram identificados com mudança de categoria.
        *   A estrutura de cada item na lista deve seguir o contrato definido em `doc/SNCT_Relatorio_Tecnico_Onboarding_v3.md`, Seção 6.2:
            ```json
            [
              {
                "id": "template_abc123", // ID do template na Gupshup
                "elementName": "Meu Template A",
                "wabaId": "1234567890", // WABA ID ao qual o template pertence
                "category": "UTILITY",
                "oldCategory": "MARKETING", // Se aplicável
                "language": "pt_BR",
                "status": "APPROVED"
                // Outros campos relevantes da API da Gupshup podem ser incluídos
              }
            ]
            ```
        *   O `wabaId` é crucial aqui para que o `sms-notifier` saiba a qual cliente a mudança se refere.
3.  **Gerenciamento de Estado para Detecção de Mudanças (Opcional, mas Recomendado para Robustez):**
    *   Avaliar a necessidade de um mecanismo mais robusto para detectar mudanças além da simples presença de `oldCategory`.
    *   Se necessário, implementar uma forma de armazenar o estado anterior dos templates (ex: em memória com TTL, ou um cache externo como Redis) e comparar com o estado atual para identificar alterações. Isso é particularmente importante se o campo `oldCategory` não for consistentemente fornecido pela Gupshup para todos os tipos de mudança ou após um certo tempo.
4.  **Logging Estruturado:**
    *   Substituir os `println` por uma biblioteca de logging estruturado (ex: `clojure.tools.logging` com uma implementação como SLF4J/Logback). Isso facilitará a análise de logs em ambientes de produção.
5.  **Mecanismo de Retentativas (Retry):**
    *   Implementar políticas de retentativa com backoff exponencial para chamadas HTTP externas (API da Gupshup e `customer-manager-service`). A biblioteca `diehard` é uma sugestão do documento de arquitetura.
6.  **Testes Abrangentes:**
    *   Escrever/atualizar testes unitários e de integração para:
        *   A lógica de busca de WABA IDs no `customer-manager-service`.
        *   A lógica de iteração e busca de templates por WABA ID.
        *   A correta formatação da resposta do endpoint `/changed-templates`.
        *   Casos de erro e resiliência (ex: `customer-manager-service` indisponível).
7.  **Revisão da Estrutura de Dados Mock:**
    *   Ajustar `mock-templates-com-mudanca` para incluir o `wabaId` e garantir que a estrutura dos dados de teste seja compatível com a nova lógica de múltiplos WABA IDs e com o formato esperado pelo endpoint `/changed-templates`.

## 5. Considerações Adicionais

*   **Segurança:** Garantir que o token da API Gupshup e quaisquer outras credenciais sejam gerenciados de forma segura (via variáveis de ambiente é uma prática comum).
*   **Performance:** Para um grande número de WABA IDs, considerar se chamadas sequenciais à Gupshup são aceitáveis ou se algum nível de paralelização seria benéfico (com cuidado para não exceder limites de taxa da API).

## 6. Próximos Passos Imediatos

1.  Implementar a integração com `customer-manager-service` para buscar a lista de WABA IDs.
2.  Desenvolver o endpoint `GET /changed-templates` com a estrutura de dados correta.
3.  Adaptar a lógica de `check-for-changes` e `fetch-templates` para operar com múltiplos WABA IDs.

Após a conclusão destas tarefas, o `notification-watcher` estará significativamente mais próximo da sua especificação final e pronto para testes de integração com os outros serviços.
