(ns roll.paths
  (:require [taoensso.timbre :refer [info]]
            [clojure.tools.namespace.repl :as nr]
            [clojure.tools.namespace.file :as nf]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [roll.watch :as w]
            [roll.util :as u]))



(defn require-reload [file]
  (-> (nf/read-file-ns-decl file)
      second
      (require :reload)))


(defn reload-clj
  "Reload clojure files using (require ... :reload). Safe for
  `defonce` declarations."
  [paths reload-config]
  (let [files (->> (map io/file paths)
                   (filter (comp #{"clj" "cljc"} w/file-suffix)))]
    (info "reloading" (pr-str (mapv u/format-parent files)))
    (try
      (doseq [f files]
        (require-reload f))
      
      (catch Throwable e 
        (println (ex-message e) "\n"
                 (ex-message (ex-cause e)) "\n")))))




(defn load-clj
  "Load clojure files. Overwrites the definitions of lib on classpath."
  [paths reload-config]
  (let [files (->> (map io/file paths)
                   (filter (comp #{"clj" "cljc"} w/file-suffix)))]
    (info "loading" (pr-str (mapv u/format-parent files)))
    (try
      (doseq [p paths]
        (load-file p))
      
      (catch Throwable e 
        (println (ex-message e) "\n"
                 (ex-message (ex-cause e)) "\n")))))




(defn refresh-clj
  "Reload clojure libs using `clojure.tools.namespace.repl/refresh`.
  Not safe for `defonce` declarations."
  [_ reload-config]
  (apply nr/set-refresh-dirs (:paths reload-config))
  (let [result (nr/refresh)]
    (when (not= :ok result)
      (println (ex-message result) "\n"
               (ex-message (ex-cause result)) "\n"))))




(defn watch-item [paths-config]
  (->> paths-config
       ;; extract paths and opts
       ((juxt #(->> % (remove map?) flatten distinct (filter string?))
              #(->> % (filter map?) (apply merge))))

       ;; init paths with opts
       ((fn [[paths {:as opts :keys [init watch]}]]
          (when-let [paths (->> (map u/normalize-path paths)
                                (filter #(if (u/exists? %) %
                                             (info "Warning: Could not open" %)))
                                vec not-empty)]
            
            (let [reload-config {:paths paths :opts opts}]
              (when init (init reload-config))
              
              (when-let [watch-fn (if (true? watch) init watch)]
                (w/add-watch!
                 reload-config
                 {:paths paths
                  :filter w/file-filter
                  :handler
                  (w/throttle
                   (or (:throttle opts) 50)
                   (bound-fn [evts]
                     (when-let [files (->> evts
                                           (mapv (comp #(.getCanonicalPath %) :file))
                                           set vec not-empty)]
                       (watch-fn files reload-config))))}))))))))




(defmethod ig/init-key :roll/paths [_ opts]
  (info "starting roll/paths:")
  (info (u/spp opts))
  
  (->> (u/resolve-syms opts)
       (#(cond-> %
           (->> % (filter map?) not-empty)
           (vector)))
       (mapv watch-item)
       last))



(defmethod ig/halt-key! :roll/paths [_ watcher]
  (info "stopping roll/paths...")

  (doseq [watch-key (keys (:watches watcher))]
    (w/remove-watch! watch-key)))









(comment

  (watch-item
   ["src/clj"
    "src/clj/roll/paths.clj"
    {:watch roll.paths/reload-clj}])


  (w/remove-watch! ["src/clj"
                    "src/clj/roll/paths.clj"])
  
  (w/stop!)
  

  {:roll/paths [["resources/public/css/main.css"
                 "resources/public/js/index.js"
                 {:init clojure.core/prn
                  :watch roll.util/read-edn
                  :throttle 1000
                  :filter ["clj" "cljs"]
                  :close clojure.core/prn}]]}
  )
