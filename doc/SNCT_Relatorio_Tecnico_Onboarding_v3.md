```markdown
# Relatório Técnico e Guia de Onboarding: Sistema de Notificação de Mudança de Categoria de Templates (SNCT)

**ID do Documento:** ARQ-SNCT-20250710-v3.0
**Data:** 10 de Julho de 2025
**Versão:** 3.0 (Arquitetura com `customer-manager-service`)
**Autor:** Gemini, Arquiteto de Sistemas

**Propósito deste Documento:** Este documento serve a um duplo propósito: (1) Registrar as decisões arquiteturais que levaram ao design atual do sistema SNCT, incluindo a gestão centralizada de clientes, e (2) Funcionar como o **guia de onboarding primário** para qualquer desenvolvedor que se junte ao projeto, fornecendo todo o contexto técnico e prático necessário para configurar o ambiente de desenvolvimento e contribuir para o código-fonte.

-----

## 1. Resumo Executivo

O Sistema de Notificação de Mudança de Categoria de Templates (SNCT) visa alertar proativamente os clientes sobre alterações nas categorias de seus templates de mensagens (ex: WhatsApp Business API via Gupshup) impostas por provedores externos como a Meta. Esta versão da arquitetura introduz um serviço dedicado de gerenciamento de clientes (`customer-manager-service`) para suportar múltiplos clientes/aplicativos e permitir a administração de informações de contato e configurações através de uma interface de front-end (futura).

A arquitetura é composta por três microsserviços principais:
*   **`customer-manager-service`**: Gerencia os dados dos clientes (WABA IDs, telefones de contato, etc.) e fornece uma API para o front-end e outros serviços internos.
*   **`notification-watcher`**: Monitora as APIs dos provedores (ex: Gupshup) para os clientes ativos (informados pelo `customer-manager-service`) em busca de mudanças de categoria de templates.
*   **`sms-notifier`**: Consome as mudanças detectadas pelo `notification-watcher`, busca os dados de contato do cliente no `customer-manager-service`, envia notificações por SMS e registra o envio para garantir idempotência.

Essa abordagem visa desacoplamento, escalabilidade e manutenibilidade.

-----

## 2. Introdução e Escopo do Problema

Clientes que utilizam plataformas de comunicação como a Gupshup para enviar mensagens via WhatsApp frequentemente criam templates de mensagens. Esses templates são categorizados pela Meta (empresa controladora do WhatsApp). Ocasionalmente, a Meta pode reclassificar um template, mudando sua categoria (ex: de "MARKETING" para "UTILITY" ou vice-versa). Essa mudança pode ter implicações financeiras ou funcionais para o cliente.

O SNCT visa:
1.  Detectar automaticamente essas mudanças de categoria.
2.  Notificar o contato apropriado do cliente via SMS.
3.  Permitir o gerenciamento de múltiplos clientes/apps e seus respectivos contatos para notificação.
4.  Fornecer uma base para futuras interfaces de gerenciamento de clientes.

-----

## 3. Arquitetura Detalhada do Sistema

O sistema é desenhado como uma arquitetura de microsserviços para promover desacoplamento, escalabilidade independente e clareza de responsabilidades.

### 3.1. Componentes Principais

**3.1.1. `customer-manager-service`**
*   **Responsabilidades:**
    *   Servir como a fonte da verdade para todos os dados relacionados aos clientes (aplicativos Gupshup/WABA IDs).
    *   Armazenar e gerenciar WABA IDs, números de telefone de contato associados, nomes de clientes e outras metadados relevantes.
    *   Expor uma API REST para:
        *   Operações CRUD (Criar, Ler, Atualizar, Deletar) de clientes por um sistema de front-end (a ser desenvolvido).
        *   Consulta da lista de WABA IDs ativos pelo `notification-watcher`.
        *   Consulta de detalhes de contato (ex: telefone) para um WABA ID específico pelo `sms-notifier`.
*   **Banco de Dados:** Dedicado (ex: PostgreSQL, CockroachDB) para armazenar informações dos clientes.
*   **Tecnologia Sugerida:** Clojure (para manter a consistência da stack), mas poderia ser qualquer linguagem/framework apropriado para APIs REST.

**3.1.2. `notification-watcher`**
*   **Responsabilidades:**
    *   Periodicamente, consultar o `customer-manager-service` para obter a lista de todos os WABA IDs ativos que precisam ser monitorados.
    *   Para cada WABA ID, consultar a API do provedor correspondente (ex: Gupshup) para buscar o status atual dos templates e suas categorias.
    *   Detectar quaisquer mudanças de categoria comparando com o estado anterior (se aplicável, ou simplesmente relatando o estado atual).
    *   Expor uma API interna (ex: `/changed-templates`) que o `sms-notifier` possa consumir para obter a lista de templates com categorias alteradas.
*   **Banco de Dados:** N/A (é um serviço sem estado direto, mas pode manter um cache em memória do estado anterior dos templates, se necessário, para otimização da detecção de mudanças).
*   **Tecnologia:** Clojure, `clj-http`, `http-kit`, `compojure`, `ring-json`.

**3.1.3. `sms-notifier`**
*   **Responsabilidades:**
    *   Periodicamente, consultar a API `/changed-templates` do `notification-watcher`.
    *   Para cada template alterado recebido:
        *   Extrair o `wabaId`.
        *   Consultar o `customer-manager-service` para obter o número de telefone de contato associado ao `wabaId`.
        *   Verificar em seu próprio banco de dados se uma notificação para essa combinação específica de template ID e nova categoria já foi enviada (garantindo idempotência).
        *   Se a notificação for nova e um telefone de contato for encontrado:
            *   Formatar a mensagem de SMS.
            *   Enviar o SMS através de um provedor de SMS (ex: Twilio, Vonage).
            *   Registrar que a notificação foi enviada em seu banco de dados.
*   **Banco de Dados:** CockroachDB (ou PostgreSQL) para armazenar a tabela `sent_notifications` (template_id, new_category, waba_id, timestamp).
*   **Tecnologia:** Clojure, `clj-http`, `next.jdbc`, `org.postgresql/postgresql`, `cheshire`.

### 3.2. Diagrama da Arquitetura

```
+----------------------+      +---------------------------+      +--------------------+
|      Front-End       |----->|  customer-manager-service |----->|   DB (Clientes)    |
| (Gerenciamento CRUD) |      |  (API REST)               |      | (WABA IDs, Contatos)|
+----------------------+      +---------------------------+      +--------------------+
                                   ^          ^
                                   |          | (1. Lista de WABA IDs ativos)
(3. GET Telefone para WABA ID) |          | (2. GET Detalhes dos Templates)
                                   |          |
+----------------------+      +---------------------------+      +--------------------+
|     sms-notifier     |<-----|   notification-watcher    |----->|  API Provedor      |
| (Polling, Envio SMS) |(4. GET /changed-templates)         |      |  (ex: Gupshup)     |
+----------------------+      +---------------------------+      +--------------------+
       |        |
       |        +------------------------------------------------+
       |                                                         |
       v                                                         v
+----------------------+      +-----------------------------------+
| DB (sent_notifications)|      | Provedor SMS (ex: Twilio API)     |
| (Idempotência)       |      |                                   |
+----------------------+      +-----------------------------------+
```

### 3.3. Fluxos de Dados Principais

**3.3.1. Fluxo de Notificação de Mudança de Categoria:**
1.  **`notification-watcher`** periodicamente (ou sob gatilho) requisita ao `customer-manager-service` a lista de `wabaId`s ativos para monitoramento.
2.  Para cada `wabaId`, o **`notification-watcher`** chama a API da Gupshup (ou outro provedor) para obter os dados dos templates.
3.  O **`notification-watcher`** detecta mudanças de categoria e as expõe em seu endpoint `/changed-templates`.
4.  O **`sms-notifier`** periodicamente faz um GET em `/changed-templates` do `notification-watcher`.
5.  Para cada template alterado:
    a.  O **`sms-notifier`** extrai o `wabaId`.
    b.  O **`sms-notifier`** consulta o `customer-manager-service` para obter o número de telefone do contato associado ao `wabaId`.
    c.  O **`sms-notifier`** verifica em seu banco `sent_notifications` se esta notificação (template ID + nova categoria) já foi processada.
    d.  Se não processada e o telefone existe, o **`sms-notifier`** envia o SMS via provedor e registra o envio no banco `sent_notifications`.

**3.3.2. Fluxo de Gerenciamento de Clientes (via Front-End):**
1.  Um usuário (administrador) acessa o **Front-End**.
2.  O **Front-End** interage com a API REST do `customer-manager-service` para:
    *   Listar clientes existentes.
    *   Adicionar um novo cliente (fornecendo WABA ID, nome, telefone de contato, etc.).
    *   Atualizar dados de um cliente existente.
    *   Remover/Desativar um cliente.
3.  O **`customer-manager-service`** valida os dados e persiste as mudanças em seu banco de dados de clientes.

-----

## 4. Pilha Tecnológica (Stack) Proposta

| Serviço                   | Linguagem/Framework | Banco de Dados                 | Bibliotecas Chave (Exemplos)                                   |
| ------------------------- | ------------------- | ------------------------------ | -------------------------------------------------------------- |
| `customer-manager-service`| Clojure 1.11+       | PostgreSQL ou CockroachDB      | `compojure`, `ring-json`, `next.jdbc`, `environ` (para config) |
| `notification-watcher`    | Clojure 1.11+       | N/A                            | `clj-http`, `http-kit`, `compojure`, `ring-json`, `environ`    |
| `sms-notifier`            | Clojure 1.11+       | CockroachDB v23.1+ (ou PG)     | `clj-http`, `next.jdbc`, `org.postgresql/postgresql`, `cheshire`, `environ` |
| **Infraestrutura Comum**  | Docker, Docker Compose, Leiningen, Java (JDK 11+) |                                |                                                                |

-----

## 5. Configuração do Ambiente de Desenvolvimento Local

**Pré-requisitos:** Git, Docker, Docker Compose, Java (JDK 11+), Leiningen.

**Passo 1: Configurar Bancos de Dados (via Docker Compose)**
Um `docker-compose.yml` deverá ser criado para gerenciar:
*   Uma instância de CockroachDB (ou PostgreSQL) para o `sms-notifier` (tabela `sent_notifications`).
*   Uma instância de PostgreSQL (ou outra instância/banco de dados no CockroachDB) para o `customer-manager-service` (tabela `customers`).

Exemplo de `docker-compose.yml` (parcial):
```yaml
version: '3.8'
services:
  cockroachdb:
    image: cockroachdb/cockroach:v23.1.9
    command: start-single-node --insecure
    ports:
      - "26257:26257" # SQL
      - "8080:8080"   # Admin UI
    volumes:
      - cockroachdb_data:/cockroach/cockroach-data

  postgres_customer_db:
    image: postgres:15
    environment:
      POSTGRES_USER: admin
      POSTGRES_PASSWORD: password
      POSTGRES_DB: customer_db
    ports:
      - "5432:5432"
    volumes:
      - postgres_customer_data:/var/lib/postgresql/data

volumes:
  cockroachdb_data:
  postgres_customer_data:
```
*Nota: Ajustar usuários, senhas e nomes de bancos conforme necessário.*

**SQL para `customer-manager-service` (Exemplo - PostgreSQL):**
```sql
CREATE TABLE customers (
    id SERIAL PRIMARY KEY,
    waba_id VARCHAR(255) UNIQUE NOT NULL,
    customer_name VARCHAR(255),
    contact_phone VARCHAR(50) NOT NULL,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
```

**SQL para `sms-notifier` (CockroachDB - já definido anteriormente):**
```sql
CREATE TABLE sent_notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    template_id VARCHAR(255) NOT NULL,
    new_category VARCHAR(100) NOT NULL,
    waba_id VARCHAR(255) NOT NULL,
    sent_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_notification UNIQUE (template_id, new_category, waba_id)
);
```

**Passo 2: Configurar os Serviços Clojure**
Cada serviço (`customer-manager-service`, `notification-watcher`, `sms-notifier`) será um projeto Leiningen separado.

*   **`customer-manager-service/project.clj` (Exemplo)**
    ```clojure
    (defproject customer-manager-service "0.1.0-SNAPSHOT"
      :dependencies [[org.clojure/clojure "1.11.1"]
                     [compojure "1.7.1"]
                     [ring/ring-json "0.5.1"]
                     [ring/ring-defaults "0.4.0"] ; Para middleware
                     [seancorfield/next.jdbc "1.3.894"]
                     [org.postgresql/postgresql "42.6.0"]
                     [environ "1.2.0"]] ; Para vars de ambiente
      :plugins [[lein-ring "0.12.6"]] ; Para rodar com servidor web
      :ring {:handler customer-manager-service.handler/app}
      :main ^:skip-aot customer-manager-service.core
      :profiles {:uberjar {:aot :all}})
    ```

*   **`notification-watcher/project.clj` (Similar ao existente, pode precisar de `environ`)**
*   **`sms-notifier/project.clj` (Similar ao existente, pode precisar de `environ`)**

**Passo 3: Variáveis de Ambiente**
Cada serviço precisará de variáveis de ambiente para configurar URLs de dependências, credenciais de banco de dados, chaves de API, etc. Recomenda-se o uso da biblioteca `environ` em Clojure para gerenciá-las.

Exemplos:
*   **`customer-manager-service`**: `DATABASE_URL` (para seu DB de clientes).
*   **`notification-watcher`**: `CUSTOMER_MANAGER_API_URL`, `GUPSHUP_API_KEY_XXX` (se as chaves variarem por cliente ou forem gerenciadas aqui).
*   **`sms-notifier`**: `WATCHER_API_URL`, `CUSTOMER_MANAGER_API_URL`, `DATABASE_URL` (para DB `sent_notifications`), `SMS_PROVIDER_API_KEY`, `SMS_PROVIDER_SENDER_ID`.

**Passo 4: Executar os Serviços**
1.  Clone os repositórios de cada serviço.
2.  Em cada diretório de serviço:
    *   `lein deps`
    *   Configure as variáveis de ambiente (ex: via arquivo `.lein-env` se usar `environ`, ou exportando no terminal).
    *   `lein run` (para workers como `sms-notifier`, `notification-watcher`) ou `lein ring server-headless <port>` (para APIs como `customer-manager-service`).
3.  Execute os serviços em terminais separados.

-----

## 6. Detalhes das APIs (Contratos Preliminares)

### 6.1. `customer-manager-service` API Endpoints

**Base URL:** `http://localhost:<port_customer_manager>`

*   **`POST /customers`**: Adicionar um novo cliente.
    *   **Request Body (JSON):**
        ```json
        {
          "waba_id": "1234567890",
          "customer_name": "Cliente Exemplo",
          "contact_phone": "+5511999998888",
          "is_active": true
        }
        ```
    *   **Response (201 Created):** Detalhes do cliente criado.
        ```json
        {
          "id": 1,
          "waba_id": "1234567890",
          "customer_name": "Cliente Exemplo",
          "contact_phone": "+5511999998888",
          "is_active": true,
          "created_at": "...",
          "updated_at": "..."
        }
        ```

*   **`GET /customers`**: Listar todos os clientes.
    *   **Query Params (Opcional):** `?active=true`
    *   **Response (200 OK):** Array de clientes.
        ```json
        [
          { "id": 1, "waba_id": "...", ... },
          { "id": 2, "waba_id": "...", ... }
        ]
        ```

*   **`GET /customers/{id}`**: Obter detalhes de um cliente específico.
    *   **Response (200 OK):** Detalhes do cliente.

*   **`PUT /customers/{id}`**: Atualizar um cliente.
    *   **Request Body (JSON):** Campos a serem atualizados.
    *   **Response (200 OK):** Detalhes do cliente atualizado.

*   **`DELETE /customers/{id}`**: Remover um cliente (ou marcar como inativo).
    *   **Response (204 No Content)**

*   **`GET /internal/customers/active-waba-ids`**: (Endpoint para `notification-watcher`)
    *   **Response (200 OK):**
        ```json
        {
          "waba_ids": ["1234567890", "0987654321"]
        }
        ```

*   **`GET /internal/customers/contact-info/{wabaId}`**: (Endpoint para `sms-notifier`)
    *   **Response (200 OK):**
        ```json
        {
          "waba_id": "1234567890",
          "contact_phone": "+5511999998888"
        }
        ```
    *   **Response (404 Not Found):** Se o `wabaId` não for encontrado ou não tiver contato.

### 6.2. `notification-watcher` API Endpoint

**Base URL:** `http://localhost:<port_notification_watcher>`

*   **`GET /changed-templates`**:
    *   **Response (200 OK):** Array de templates alterados.
        ```json
        [
          {
            "id": "template_abc123",
            "elementName": "Meu Template A",
            "wabaId": "1234567890",
            "category": "UTILITY",
            "oldCategory": "MARKETING",
            "language": "pt_BR",
            "status": "APPROVED"
            // Outros campos relevantes da API da Gupshup
          }
        ]
        ```
        *Nota: `oldCategory` pode ser opcional se a lógica de detecção de mudança for apenas baseada no estado atual vs. um estado esperado/anterior não fornecido diretamente pela Gupshup nesta chamada.*

-----

## 7. Roteiro de Desenvolvimento e Próximos Passos (Backlog)

Este roteiro foca na implementação da nova arquitetura.

**Fase 1: Fundação - `customer-manager-service`**
*   `[ ]` **(customer-manager):** Definir o esquema final do banco de dados para clientes.
*   `[ ]` **(customer-manager):** Implementar as operações CRUD básicas da API (POST, GET all, GET by ID, PUT, DELETE).
*   `[ ]` **(customer-manager):** Implementar os endpoints internos: `/internal/customers/active-waba-ids` e `/internal/customers/contact-info/{wabaId}`.
*   `[ ]` **(customer-manager):** Escrever testes unitários e de integração para a API.
*   `[ ]` **(infra):** Configurar o `docker-compose.yml` com o banco de dados do `customer-manager-service`.

**Fase 2: Integração e Adaptação dos Serviços Existentes**
*   `[ ]` **(notification-watcher):** Modificar para buscar a lista de `wabaId`s do `customer-manager-service`.
*   `[ ]` **(notification-watcher):** Adaptar a lógica para iterar sobre múltiplos `wabaId`s e consultar a API da Gupshup para cada um.
*   `[ ]` **(notification-watcher):** Garantir que o endpoint `/changed-templates` inclua o `wabaId` em cada objeto de template.
*   `[ ]` **(sms-notifier):** Modificar a função `get-contact-phone-for-app` para chamar o endpoint `/internal/customers/contact-info/{wabaId}` do `customer-manager-service`.
*   `[ ]` **(ambos os watchers):** Implementar tratamento de erro robusto para chamadas de API (ex: `customer-manager-service` indisponível).
*   `[ ]` **(testes):** Atualizar/criar testes de integração entre os serviços.

**Fase 3: Robustez, Logging e Deploy**
*   `[ ]` **(todos):** Integrar uma biblioteca de logging estruturado (ex: `tools.logging` com SLF4J/Logback).
*   `[ ]` **(todos):** Implementar mecanismos de retentativas (retry) com backoff para chamadas de API entre serviços e para APIs externas (ex: biblioteca `diehard`).
*   `[ ]` **(todos):** Criar perfis Leiningen (`profiles`) para gerenciar configurações de `dev` e `prod`.
*   `[ ]` **(todos):** Construir "uberjars" standalone para facilitar o deploy.
*   `[ ]` **(todos):** "Dockerizar" todas as três aplicações Clojure.
*   `[ ]` **(infra):** Planejar o deploy em produção (provisionamento de VMs/contêineres, configuração de rede, segurança).

**Fase 4: Front-End (Escopo Futuro)**
*   `[ ]` **(front-end):** Desenvolver a interface de usuário para gerenciar clientes, consumindo a API do `customer-manager-service`.

-----

**Fim do Documento**
```
