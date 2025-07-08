(ns notification-watcher.gupshup-service
  (:require [clojure.spec.alpha :as s]
            [clj-http.client :as client]))

;; --- Definição da "Forma" dos Dados (Schema) ---
(s/def ::id string?)
(s/def ::elementName string?)
(s/def ::status #{"APPROVED"})
(s/def ::data string?)
(s/def ::template (s/keys :req-un [::id ::elementName ::status ::data]))


;; --- Funções do Serviço ---

(defn- fetch-raw-data
  "Função privada responsável APENAS por fazer a chamada HTTP."
  []
  (let [api-key (System/getenv "GUPSHUP_TOKEN")
        app-id  (System/getenv "GUPSHUP_APP_ID") ; <-- NOVO: Lendo o APP_ID
        base-url "https://api.gupshup.io/sm/api/v1/template/list"]

    (if-not (and api-key app-id)
      (println "ERRO: As variáveis de ambiente GUPSHUP_TOKEN e/ou GUPSHUP_APP_ID não estão definidas.")
      (try
        ;; --- MUDANÇA IMPORTANTE AQUI ---
        (let [full-url (str base-url "/" app-id) ; <-- Montando a URL completa
              response (client/get full-url
                                   {:headers          {:apikey api-key}
                                    :as               :json
                                    :throw-exceptions false
                                    :conn-timeout     5000
                                    :socket-timeout   5000})
              status (:status response)]
          (if (<= 200 status 299)
            (:body response)
            (do
              (println (str "ERRO: API da Gupshup retornou status " status ". Resposta: " (:body response)))
              nil)))
        (catch Exception e
          (println (str "ERRO: Falha crítica na comunicação com a API da Gupshup: " (.getMessage e)))
          nil)))))

(defn- parse-and-clean-templates
  "Esta função não muda. A responsabilidade dela é só limpar os dados."
  [raw-data]
  (->> (get-in raw-data [:templates] [])
       (filter #(= "APPROVED" (:status %)))
       (map #(select-keys % [:id :elementName :status :data]))
       (filter #(s/valid? ::template %))
       (doall)))

(defn get-approved-templates
  "Esta função não muda. Ela continua orquestrando o processo."
  []
  (println "Buscando templates da Gupshup...")
  (if-let [raw-data (fetch-raw-data)]
    (do
      (println "Dados recebidos. Limpando e validando templates...")
      (let [approved-templates (parse-and-clean-templates raw-data)]
        (println (str (count approved-templates) " templates aprovados e prontos para uso."))
        approved-templates))
    []))
