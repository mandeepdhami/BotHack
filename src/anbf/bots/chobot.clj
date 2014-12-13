(ns anbf.bots.chobot
  (:require [clojure.tools.logging :as log]
            [flatland.ordered.set :refer [ordered-set]]
            [anbf.anbf :refer :all]
            [anbf.item :refer :all]
            [anbf.itemtype :refer :all]
            [anbf.itemid :refer :all]
            [anbf.handlers :refer :all]
            [anbf.player :refer :all]
            [anbf.pathing :refer :all]
            [anbf.monster :refer :all]
            [anbf.position :refer :all]
            [anbf.game :refer :all]
            [anbf.dungeon :refer :all]
            [anbf.level :refer :all]
            [anbf.tile :refer :all]
            [anbf.delegator :refer :all]
            [anbf.util :refer :all]
            [anbf.behaviors :refer :all]
            [anbf.sokoban :refer :all]
            [anbf.tracker :refer :all]
            [anbf.actions :refer :all]))

(defn pray [game]
  (with-reason "pray"
    (if (can-pray? game)
      (->Pray))))

(defn- hostile-dist-thresh [game]
  (cond
    (planes (branch-key game)) 1
    (= :sokoban (branch-key game)) 50
    :else 5))

(defn- hostile-threats [{:keys [player] :as game}]
  (->> (curlvl-monsters game)
       (filter #(and (hostile? %)
                     (or (adjacent? player %)
                         (and (not (and (blind? player) (:remembered %)))
                              (> 10 (- (:turn game) (:known %)))
                              (> (hostile-dist-thresh game) (distance player %))
                              (not (blind? player))
                              (not (hallu? player))))))
       set))

(defn- threat-map [game]
  (into {} (for [m (hostile-threats game)] [(position m) m])))

(defn enhance [game]
  (if (:can-enhance (:player game))
    (enhance-all)))

(defn- handle-starvation [{:keys [player] :as game}]
  (or (if (and (weak? player) (not (overloaded? player)))
        (if-let [[slot food] (have game (every-pred (partial can-eat? player)
                                                    (complement tin?))
                                   #{:bagged})]
          (with-reason "weak or worse, eating" food
            (or (unbag game slot food)
                (->Eat slot)))))
      (if (and (fainting? (:player game))
               (can-pray? game))
        (with-reason "praying for food" ->Pray))))

(defn- handle-illness [{:keys [player] :as game}]
  (or (if (:stoning player)
        (with-reason "fix stoning"
          (if-let [[slot item] (have game "lizard corpse" #{:bagged})]
            (or (unbag game slot item)
                (->Eat slot))
            (pray game))))
      (if (:lycantrophy player)
        (with-reason "fix lycantrophy"
          (if-let [[slot item] (have game "sprig of wolfsbane" #{:bagged})]
            (or (unbag game slot item)
                (->Eat slot))
            (pray game))))
      (if-let [[slot _] (and (unihorn-recoverable? game)
                             ; rest can wait
                             (or (some (:state player) #{:conf :stun :ill})
                                 (and (not (have-intrinsic? player :telepathy))
                                      (blind? player)))
                             (have-unihorn game))]
        (with-reason "applying unihorn to recover" (->Apply slot)))
      (if (:ill (:state player))
        (with-reason "fixing illness"
          (or (if-let [[slot _] (have game "eucalyptus leaf" #{:noncursed})]
                (->Eat slot))
              (if-let [[slot item] (or (have game "potion of healing"
                                          {:buc :blessed :bagged true})
                                       (have game #{"potion of extra healing"
                                                    "potion of full healing"}
                                          #{:noncursed :bagged}))]
                (or (unbag game slot item)
                    (->Quaff slot))))))))

(defn name-first-amulet [anbf]
  (reify ActionHandler
    (choose-action [this game]
      (when-let [[slot _] (have game "Amulet of Yendor")]
        (deregister-handler anbf this)
        (with-reason "naming the real amulet"
          (->Name slot "REAL"))))))

(defn real-amulet? [item]
  (and (= "Amulet of Yendor" (:name item))
       (= "REAL" (:specific item))))

(defn get-amulet [game]
  (if-not (have game real-amulet?)
    (with-reason "searching for the amulet"
      (or (explore game)
          (search-level game 1) ; if Rodney leaves the level with it we're screwed
          (seek game stairs-up?)))))

(defn have-throwable [game]
  (or (have game (some-fn dagger? short-sword?) #{:can-remove})
      (have game (some-fn dart? ammo?) #{:can-remove})
      (have game rocks? #{:can-remove})))

(defn castle-plan-b [{:keys [player] :as game}]
  (let [level (curlvl game)]
    (if (:castle (:tags level))
      (if (and (not (get-level game :main :votd))
               (or (not (have-levi game))
                   (not-any? (:genocided game) #{";" "electric eel"})
                   (not (reflection? game))) )
        (or (with-reason "castle plan B"
              (if-let [[slot scroll] (have game "scroll of earth"
                                           #{:noncursed})]
                (with-reason "using scroll of earth"
                  (if-let [{:keys [step]} (navigate game (position 12 12))]
                    (or step (->Read slot))))
                (with-reason "using wand of cold"
                  (if-let [[slot _] (have game "wand of cold")]
                    (if-let [pool (and (not-any? walkable? (for [y [11 12 13]]
                                                             (at level 13 y)))
                                       (find-first pool? (for [y [11 12 13]]
                                                           (at level 13 y))))]
                      (if-let [{:keys [step]}
                               (navigate game #(and (= 2 (distance pool %))
                                                    (in-line pool %)))]
                        (or step (->ZapWandAt slot (towards player pool)))))))))
            (if-let [wow (find-first (complement :seen) (for [y [7 17]
                                                              x [12 66]]
                                                          (at level x y)))]
              (with-reason "getting WoW"
                (:step (navigate game wow #{:no-traps})))))))))

(defn full-explore [game]
  (with-reason "full-explore"
    (if-not (get-level game :main :sanctum)
      (or (explore game :mines :minetown)
          (explore game :main :sokoban)
          (if (and (have game "Excalibur") (have-throwable game))
            (do-soko game))
          (explore game :main :quest)
          (let [minetown (get-level game :mines :minetown)]
            (if (or (some->> game have-key secondv key?)
                    (not (:minetown-grotto (:tags minetown)))
                    (:seen (at minetown 48 5)))
              (explore game :mines)))
          (explore game :main "Dlvl:20")
          (if (and (have-levi game) (<= 14 (:xplvl (:player game))))
            (explore game :quest))
          (explore game :main :medusa)
          (castle-plan-b game)
          (explore game :main :castle :exclusive)
          (explore game :vlad)
          (explore game :main)
          (explore game :wiztower)
          (invocation game)))))

(defn endgame? [game]
  (get-level game :main :sanctum))

(defn progress [game]
  (with-reason "progress"
    (if-not (endgame? game)
      (full-explore game)
      (or (get-amulet game)
          (visit game :astral)
          (seek-high-altar game)))))

(defn- pause-condition?
  "For debugging - pause the game when something occurs"
  [game]
  #_(< 3205 (:turn game))
  #_(= {:x 33 :y 14} (position (:player game)))
  #_(= :sokoban (branch-key game))
  #_(:oracle (curlvl-tags game))
  #_(= :astral (branch-key game))
  #_(= "Dlvl:46" (:dlvl game))
  #_(explored? game :main :end)
  #_(and (= :wiztower (branch-key game))
       (:end (curlvl-tags game))
  #_(and (= :vlad (branch-key game))
       (:end (curlvl-tags game)))
  #_(have game candelabrum)
  #_(have game "Orb of Fate")
  ))

(def desired-weapons
  (ordered-set "Grayswandir" "Excalibur" "Mjollnir" "Stormbringer"
               "katana" "long sword"))

(def desired-suit
  (ordered-set "gray dragon scale mail" "silver dragon scale mail" "dwarvish mithril-coat" "elven mithril-coat" "scale mail"))

(def desired-shirt
  (ordered-set "T-shirt" "Hawaiian shirt"))

(def desired-boots
  (ordered-set "speed boots" "iron shoes"))

(def desired-shield
  (ordered-set "shield of reflection" "small shield"))

(def desired-cloak
  (ordered-set "cloak of magic resistance" "cloak of protection" "oilskin cloak"  "cloak of displacement" "elven cloak" "dwarvish cloak"))

(def desired-helmet
  (ordered-set "helm of telepathy" "helm of brilliance" "dwarvish iron helm" "orcish helm"))

(def desired-gloves
  (ordered-set "gauntlets of power" "gauntlets of dexterity" "leather gloves"))

(def blind-tool (ordered-set "blindfold" "towel"))

(def always-desired #{"magic lamp" "wand of wishing" "wand of death" "scroll of genocide" "scroll of identify" "scroll of remove curse" "scroll of enchant armor" "scroll of enchant weapon" "scroll of charging" "potion of gain level" "potion of full healing" "potion of extra healing" "potion of see invisible"})

(def desired-items
  [(ordered-set "pick-axe" #_"dwarvish mattock") ; currenty-desired presumes this is the first category
   (ordered-set "skeleton key" "lock pick" "credit card")
   (ordered-set "ring of levitation" "boots of levitation")
   ;#{"ring of slow digestion"}
   #{"ring of conflict"}
   #{"ring of regeneration"}
   #{"ring of invisibility"}
   #{"Orb of Fate"}
   blind-tool
   (ordered-set "oil lamp" "brass lantern")
   #{"unicorn horn"}
   #{"Candelabrum of Invocation"}
   #{"Bell of Opening"}
   #{"Book of the Dead"}
   #{"lizard corpse"}
   #{"sprig of wolfsbane"}
   ;#{"bag of holding"}
   desired-cloak
   desired-suit
   desired-shield
   desired-shirt
   desired-boots
   desired-helmet
   desired-gloves
   #{"scroll of earth"}
   #{"scroll of teleportation"}
   #{"amulet of reflection"}
   #{"amulet of life saving"}
   #{"amulet of ESP"}
   #{"wand of fire"}
   #{"wand of cold"}
   #{"wand of lightning"}
   #{"wand of teleportation"}
   #{"wand of striking"}
   #{"wand of digging"}
   desired-weapons])

(def desired-singular (set (apply concat desired-items)))

(defn entering-shop? [game]
  (some->> (:last-path game) firstv (at-curlvl game) shop?))

(defn desired-food [game]
  (let [min-nw (if (< 2000 (nutrition-sum game))
                 (nw-ratio-avg game)
                 24)]
    (for [food (:food item-kinds)
          :when (and (not (egg? food))
                     (not (tin? food))
                     (not (corpse? food))
                     (> (nw-ratio food) min-nw))]
      (:name food))))

(defn desired-throwables [game]
  (let [amt-daggers (have-sum game dagger? #{:noncursed})
        amt-ammo (have-sum game (some-fn dart? ammo?) #{:noncursed})
        amt-rocks (have-sum game rocks? #{:noncursed})]
    (cond
      (< 5 amt-daggers) []
      (< 6 amt-ammo) daggers
      (< 6 amt-rocks) (concat daggers ammo)
      :else (concat daggers ammo ["rock"]))))

(defn utility
  ([item]
   (cond-> 0
     (artifact? item) (+ 50)
     (:erosion item) (- (:erosion item))
     (:enchantment item) (+ (:enchantment item))
     (:charges item) (+ (:charges item))
     (:proof item) (+ 3)
     (blessed? item) (+ 2)
     (uncursed? (:buc item)) inc
     (cursed? item) dec))
  ([game item]
   (+ (utility item)
      (let [iname (item-name game item)
            cat (find-first #(% iname) desired-items)]
        (if-let [cat-vec (and (some? cat) (vec cat))]
          (* 15 (- (count cat-vec) (.indexOf cat-vec iname)))
          0)))))

(defn- want-protection? [game]
  (< (:protection (:player game)) 3))

(defn- want-gold? [game]
  (and (want-protection? game)
       (< (gold game) (* 400 (inc (:xplvl (:player game)))))))

(defn currently-desired
  "Returns the set of item names that the bot currently wants."
  [{:keys [player] :as game}]
  (loop [cs (if (or (entering-shop? game) (shop? (at-player game)))
              (rest desired-items) ; don't pick that pickaxe back up
              desired-items)
         res always-desired]
    (if-let [c (first cs)]
      (if-let [[slot i] (max-by (comp utility secondv)
                                (have-all game c #{:bagged}))]
        (recur (rest cs)
               (into (conj res (:name i))
                     (take-while (partial not= (item-name game i)) c)))
        (recur (rest cs) (into res c)))
      (as-> res res
        (into res (desired-food game))
        (into res (desired-throwables game))
        (if-let [sanctum (get-level game :main :sanctum)]
          (if (and (not (have game real-amulet?))
                   (:seen (at sanctum 20 11)))
            (conj res "Amulet of Yendor"))
          res)
        (cond-> res
          (not (have-intrinsic? player :speed)) (conj "wand of speed monster")
          (want-gold? game) (conj "gold piece"))))))

(defn- handle-impairment [{:keys [player] :as game}]
  (or (if (and (has-hands? player)
               (:ext-blind (:state player)))
        (with-reason "fixing external blindness" ->Wipe))
      (if-let [[slot _] (and (unihorn-recoverable? game)
                             (have-unihorn game))]
        (with-reason "applying unihorn to recover" (->Apply slot)))
      (if-let [[slot _] (have game blind-tool #{:in-use :noncursed})]
        (with-reason "unblinding self"
          (->Remove slot)))
      (if (or (impaired? player) (:polymorphed player))
        (with-reason "waiting out impairment" (->Repeated (->Wait) 10)))))

(defn- take-cursed? [game item]
  (or (#{"levitation boots" "speed boots" "water walking boots" "cloak of displacement" "cloak of invisibility" "cloak of magic resistance" "cloak of protection" "gauntlets of dexterity" "gauntlets of power" "helm of brilliance" "helm of opposite alignment" "helm of telepathy" "shield of reflection" "long sword" "bag of holding" "unicorn horn" "scroll of identify"} (item-name game item))
      ((some-fn ring? amulet? tool? artifact?) item)))

(defn want-buy? [game item]
  false) ; TODO

;(if-not (resolve 'desired))
(def desired (atom #{}))

(defn- worthwhile? [game item]
  (let [id (item-name game item)]
    (and (not (> -1 (enchantment item)))
         (not (tin? item))
         (not (and ((some-fn potion? scroll? wand? ring? amulet?) item)
                   (know-id? game item)
                   (not (desired-singular id))
                   (not (@desired id))))
         (not= "bag of tricks" id)
         (not (and (have-intrinsic? (:player game) :speed)
                   (= "wand of speed monster" id)))
         (or (charged? item) (= "wand of wishing" id))
         (or (and (not= :cursed (:buc item))
                  (> 2 (or (:erosion item) 0)))
             (take-cursed? game item))
         (if (:cost item)
           (want-buy? game item)
           true))))

(defn- should-try?
  [game item]
  (and (not (:cost item))
       (not (know-id? game item))
       (or (and (wand? item)
                ((some-fn (every-pred (complement :engrave)
                                      (complement (partial tried? game)))
                          (comp nil? :target)) (item-id game item)))
           (and ((some-fn scroll? potion? ring? amulet? armor?) item)
                (not (tried? game item))
                (safe? game item)))))

(defn take-selector [{:keys [player] :as game}]
  (fn [item]
    (or (real-amulet? item)
        (and (can-take? item)
             (worthwhile? game item)
             (let [id (item-name game item)]
               (and (or (@desired id)
                        (should-try? game item)
                        (and (some @desired (possible-names game item))
                             (not (potion? item))))
                    (if-let [[_ o] (and (desired-singular id)
                                        (have game id
                                              #{:bagged :can-remove}))]
                      (> (utility item) (utility o))
                      true)))))))

(defn examine-containers [game]
  (if-let [[slot item] (have game explorable-container?)]
    (with-reason "learning contents of" item
      (->Apply slot))))

(defn- unlockable-chest? [game tile]
  (and (not (shop? tile))
       (some :locked (:items tile))
       (or (have-key game)
           (have game dagger? #{:noncursed}))))

(defn examine-containers-here [game]
  (or (if (some explorable-container? (:items (at-player game)))
        (with-reason "learning contents of containers on ground"
          ->Loot))
      (if (unlockable-chest? game (at-player game))
        (without-levitation game
          (with-reason "unlock chest"
            (or (if-let [[slot _] (have-key game)]
                  (->Unlock slot \.))
                (if-let [[slot _] (or (have game dagger? #{:safe})
                                      (have game dagger? #{:noncursed}))]
                  (or (make-use game slot)
                      ->ForceLock))))))))

(defn consider-items-here [{:keys [player] :as game}]
  (if (seq (:items (at-player game)))
    (let [to-take? (take-selector game)
          level (curlvl game)
          tile (at level player)]
      (or (if-let [to-get (and (not (shop? tile))
                               (seq (for [item (lootable-items tile)
                                          :when (to-take? item)]
                                      (:label item))))]
            (with-reason "looting desirable items"
              (without-levitation game
                (take-out \. (reduce #(assoc %1 %2 nil) {} to-get))))
            (log/debug "no desired lootable items"))
          (if-let [to-get (seq (for [item (:items tile)
                                     :when (to-take? item)]
                                 (:label item)))]
            (with-reason "getting desirable items"
              (without-levitation game
                (if (and (or (pit? tile) (spikepit? tile))
                         (not (:trapped player)))
                  (with-reason "getting into a pit"
                    (arbitrary-move game level))
                  (->PickUp (->> to-get set vec)))))
            (log/debug "no desired items here"))))))

(defn consider-items [{:keys [player] :as game}]
  (let [to-take? (take-selector game)]
    (if-let [{:keys [step target]}
             (navigate game #(or (:new-items %)
                                 (some explorable-container? (:items %))
                                 (unlockable-chest? game %)
                                 (some to-take? (concat (:items %)
                                                        (lootable-items %))))
                       #{:no-fight})]
      (with-reason "new or desired item at" target step)
      (log/debug "no desirable items anywhere"))))

(defn uncurse-weapon [game]
  (if-let [[_ weapon] (wielding game)]
    (if-let [[slot scroll] (and (cursed? weapon)
                                (have game "scroll of remove curse"
                                      #{:noncursed :bagged}))]
      (with-reason "uncursing weapon" (:label weapon)
        (or (if (not (cursed? (secondv (wielding game))))
              (if-let [[slot item] (have game cursed? {:in-use false})]
                (with-reason "wield for extra uncurse"
                  (->Wield slot))))
            (unbag game slot scroll)
            (->Read slot))))))

(defn- wield-weapon [{:keys [player] :as game}]
  (or (if-let [excal (find-first #(= "Excalibur" (item-name game %))
                                 (:items (at-player game)))]
        (->PickUp (:label excal)))
      (if-let [[slot weapon] (some (partial have-usable game) desired-weapons)]
        (if-not (or (:wielded weapon) (= :rub (typekw (:last-action game))))
          (or (uncurse-weapon game)
              (with-reason "wielding better weapon -" (:label weapon)
                (make-use game slot)))))))

(defn- choose-amulet [{:keys [player] :as game}]
  (or (and (not (have game #{"silver dragon scale mail"
                             "shield of reflection"} #{:in-use}))
           (have game "amulet of reflection" #{:can-use :bagged}))
      (have game "amulet of life saving" #{:can-use :bagged})
      (have game "amulet of ESP" #{:can-use :bagged})))

(defn- wear-amulet [game]
  (with-reason "wear amulet"
    (if-let [[slot item] (and (not= :remove (typekw (:last-action game)))
                              (choose-amulet game))]
      (or (unbag game slot item)
          (make-use game slot)))))

(defn- wear-armor [{:keys [player] :as game}]
  (first (for [category [desired-shield desired-boots desired-shirt
                         desired-suit desired-cloak desired-helmet
                         desired-gloves]
               :let [[slot armor] (some (partial have-usable game) category)]
               :when (and armor (not (:in-use armor))
                          (not= :takeoff (typekw (:last-action game))))]
           (with-reason "wearing better armor"
             (make-use game slot)))))

(defn light? [item]
  (and (not= "empty" (:specific item))
       (not (:cost item))
       (= :light (item-subtype item))
       (= :copper (item-id item))))

(defn bless-gear [game]
  (or (if-let [[slot item] (have game #{"Orb of Fate" "unicorn horn"
                                        "luckstone" "bag of holding"}
                                 #{:nonblessed :know-buc})]
        (bless game slot))
      (if-let [[_ item] (have game (every-pred cursed? :in-use))]
        (if-let [[slot scroll] (have game "scroll of remove curse"
                                     #{:noncursed :bagged})]
          (with-reason "uncursing" (:label item)
            (or (unbag game slot scroll)
                (->Read slot)))))))

(defn lit-mines? [game level]
  (and (= :mines (branch-key game))
       (if-let [floors (seq (filter #(and (floor? %)
                                          (< 5 (distance % (:player game))))
                                    (tile-seq level)))]
         (not-any? blank? floors))))

(defn- want-light? [game level]
  (not (or (explored? game)
           (:minetown (:tags level))
           (#{:air :water} (branch-key game))
           (lit-mines? game level))))

(defn use-light [game level]
  (or (if-let [[slot item] (have game (every-pred :lit light? game))]
        (if (and (not (could-be? game "magic lamp" item))
                 (not (want-light? game level)))
          (with-reason "saving energy" (->Apply slot))))
      (if-let [[slot _] (have game #(and (could-be? game "magic lamp" %)
                                         (not (or (:cost %) (:lit %)))))]
        (with-reason "using magic lamp" (->Apply slot)))
      (if (and (want-light? game level) (not (have game :lit)))
        (if-let [[slot _] (have game light?)]
          (with-reason "using any light source" (->Apply slot))))))

(defn remove-rings [{:keys [player] :as game}]
  (or (if-let [[slot _] (have game #{"ring of invisibility" "ring of conflict"}
                              #{:in-use})]
        (with-reason "don't need ring"
          (remove-use game slot)))
      (if-let [[slot _] (and (= (:hp player) (:maxhp player))
                             (have game "ring of regeneration" #{:in-use}))]
        (with-reason "don't need regen"
          (remove-use game slot)))))

(defn drop-junk [game]
  (or (if-let [[slot item] (have game (complement (partial worthwhile? game))
                                 #{:can-remove :bagged})]
        (with-reason "dropping junk"
          (or (remove-use game slot)
              (unbag game slot item)
              (->Drop slot))))
      (loop [[cat & more] desired-items]
        (let [cat-items (have-all game cat #{:bagged})
              stuck? (fn [[stuck-slot stuck-item]]
                       (and (:in-use stuck-item)
                            (not (can-remove? game stuck-slot))))]
          (if (or (more-than? 2 cat-items)
                  (and (more-than? 1 cat-items) (not-any? stuck? cat-items)))
            (if-let [[slot item] (min-by (comp (partial utility game) secondv)
                                         (remove stuck? cat-items))]
              (with-reason "dropping less useful duplicate"
                (or (remove-use game slot)
                    (unbag game slot item)
                    (if-not (:in-use item)
                      (->Drop slot)))))
            (if (seq more)
              (recur more)))))
      (if-let [[slot item] (have game #(and (rocks? %) (< 7 (:qty %))))]
        (with-reason "dropping rock excess"
          (->Drop slot (- (:qty item) 7))))))

(defn- remove-unsafe [game]
  (if-let [[slot _] (have game #(not (safe? game %)) #{:can-remove})]
    (with-reason "removing potentially unsafe item"
      (remove-use game slot))))

(defn- remove-levi
  ([game] (remove-levi game nil))
  ([game path]
   (if-let [[slot _] (and (not (needs-levi? (at-player game)))
                          (not (#{:water :air} (:branch-id game)))
                          (not-any? needs-levi? path)
                          (have-levi-on game))]
     (with-reason "don't want levi"
       (remove-use game slot)))))

(defn reequip [game]
  (let [level (curlvl game)
        tile-path (mapv (partial at level) (:last-path game))
        step (first tile-path)
        branch (branch-key game)]
    (or (wear-amulet game)
        (bless-gear game)
        (drop-junk game)
        (wear-armor game)
        (remove-unsafe game)
        (remove-rings game)
        (if-let [[slot i] (and (not (have-intrinsic? game :speed))
                               (have game "wand of speed monster" #{:bagged}))]
          (with-reason "zapping self with /oSpeed"
            (or (unbag game slot i) (->ZapWandAt slot \.))))
        (if (and (not= :wield (some-> game :last-action typekw))
                 step (not (:dug step))
                 (every? walkable? tile-path))
          (if-let [[slot item] (and (#{:air :fire :earth} branch)
                                    (not-any? portal? (tile-seq level))
                                    (have game real-amulet?))]
            (if-not (:in-use item)
              (with-reason "using amulet to search for portal"
                (make-use game slot)))
            (with-reason "reequip - weapon"
              (wield-weapon game))))
        (use-light game level)
        (remove-levi game tile-path))))

(defn- bait-wizard [game level monster]
  (if (and (= :magenta (:color monster)) (= \@ (:glyph monster))
           (not= :water (branch-key game))
           ((some-fn pool? lava?) (at level monster)))
    (with-reason "baiting possible wizard away from water/lava"
      ; don't let the book fall into water/lava
      (or (:step (navigate game #(every? (not-any-fn? lava? pool?)
                                         (neighbors level %))))
          (->Wait)))))

(defn- bait-giant [game level monster]
  (if (and (= \H (:glyph monster)) (= "Home 3" (:dlvl level))
           (not (have-pick game)) (= 12 (:y monster))
           (< 18 (:x monster) 25))
    ; only needed until the bot can use wand of striking to break blocking boulders
    (with-reason "baiting giant away from corridor"
      (or (:step (navigate game #{(position 26 12)
                                  (position 16 12)}))
          (->Wait)))))

(defn- ranged [game monster]
  ; TODO wands
  (with-reason "ranged combat"
    (if-let [[slot _] (have-throwable game)]
      (->Throw slot (towards (:player game) monster)))))

(defn- hit-floating-eye [{:keys [player] :as game} monster]
  (if (and (adjacent? player monster)
           (= "floating eye" (typename monster)))
    (with-reason "killing floating eye"
      (or (wield-weapon game)
          (if (or (blind? player)
                  (reflection? game)
                  (free-action? game))
            (->Attack (towards player monster)))
          (if-let [[slot _] (have game blind-tool #{:noncursed})]
            (->PutOn slot))
          (ranged game monster)))))

(defn corrodeproof-weapon? [item]
  (and (weapon? item)
       (or (artifact? item)
           (:proof item))))

(defn hit-corrosive [game monster]
  (with-reason "hitting corrosive monster" monster
    (if (corrosive? monster)
      (or (if-let [[slot item] (have game corrodeproof-weapon?
                                     #{:can-use :noncursed})]
            (make-use game slot)
            (if-let [[_ w] (wielding game)]
              (if (not (cursed? w))
                (->Wield \-))))
          (->Move (towards (:player game) monster))))))

(defn mobile? [game monster]
  (and (not (mimic? monster))
       (not (sessile? monster))
       (or (:awake monster)
           (> 6 (- (:turn game) (:first-known monster))))))

(defn kite [{:keys [player] :as game} monster]
  (if (and (adjacent? player monster)
           (#{"black pudding" "brown pudding" "dwarf" "mumak"}
                     (typename monster))
           (mobile? game monster)
           (not (:just-moved monster)))
    (with-reason "kite"
      (:step (navigate game #(= 2 (distance monster %))
                       {:max-steps 1 :no-traps true
                        :no-fight true :walking true})))))

(defn hit-eel [game monster]
  (if (and (pool? (at-curlvl game monster)) (not (flies? monster)))
    (if (pool? (at-player game))
      (with-reason "away from water"
        (:step (navigate game floor? {:no-fight true :max-steps 6}))))))

(defn hit-surtur [game monster]
  (if-let [[slot item] (and (= "Lord Surtur" (:name monster))
                            (have game "wand of cold"))]
    (->ZapWandAt slot (towards (:player game) monster))))

(defn hit-leprechaun [game monster]
  (if-let [qty (and (leprechaun? monster)
                    (some-> (have game "gold piece") secondv :qty))]
    (->DropSingle \$ qty)))

(defn- hit [{:keys [player] :as game} level monster]
  (with-reason "hitting" monster
    (or (bait-wizard game level monster)
        (bait-giant game level monster)
        (if-let [[slot _] (and (= :air (branch-key game))
                               (not (have-levi-on game))
                               (have-levi game))]
          (with-reason "levitation for :air"
            (make-use game slot)))
        (if (adjacent? player monster)
          (or (hit-eel game monster)
              (hit-leprechaun game monster)
              (hit-surtur game monster)
              (hit-floating-eye game monster)
              (kite game monster)
              (hit-corrosive game monster)
              (wield-weapon game)
              (if (or (not (monster? (at level monster)))
                      (#{\I \1 \2 \3 \4 \5} (:glyph monster)))
                (->Attack (towards player monster)))
              (->Move (towards player monster)))))))

(defn- kill-engulfer [{:keys [player] :as game}]
  (if (:engulfed player)
    (with-reason "killing engulfer" (or (wield-weapon game)
                                        (->Move :E)))))

(defn- low-hp? [{:keys [hp maxhp] :as player}]
  (or (< hp 10)
      (<= (/ hp maxhp) 9/20)))

(defn can-ignore? [{:keys [player] :as game} monster]
  (or (passive? monster)
      (unicorn? monster)
      (and (pool? (at-curlvl game monster)) (not (flies? monster))
           (not-any? pool? (neighbors (curlvl game) player)))
      (#{"grid bug" "newt" "leprechaun"} (typename monster))
      (and (mimic? monster) (not (adjacent? player monster)))))

(defn can-handle? [{:keys [player] :as game} monster]
  (cond
    (and (drowner? monster) (pool? (at-curlvl game monster))) false
    (= "floating eye" (typename monster)) (or (blind? player)
                                              (reflection? game)
                                              (free-action? game)
                                              (have-throwable game)
                                              (have game blind-tool
                                                    #{:noncursed}))
    (#{"spotted jelly"
       "ochre jelly"} (typename monster)) (and (have game corrodeproof-weapon?)
                                               (> (:hp player) 60))
    :else true))

(defn- engrave-slot [game perma?]
  (or (if perma?
        (firstv (or (have game #{"wand of fire"
                                 "wand of lightning"})
                    (have game "wand of digging"))))
      \-))

(defn engrave-e
  ([game] (engrave-e game false))
  ([{:keys [player] :as game} perma?]
   (with-reason "engrave E"
     (let [tile (at-player game)
           append? (or (e? tile) (less-than? 200 (:engraving tile)))]
       (if (and (engravable? tile)
                (not (perma-e? tile)))
         (or (remove-levi game)
             (if-not (not (can-engrave? game))
               (->Engrave (engrave-slot game perma?) "Elbereth" append?))))))))

(defn pray-for-hp [{:keys [player] :as game}]
  (if (and (can-pray? game)
           (or (> 6 (:hp player))
               (>= (quot (:maxhp player) 7) (:hp player))))
    (with-reason "praying for hp" ->Pray)))

(defn exposed? [game level pos]
  (->> (neighbors level pos)
       (filter (some-fn walkable? water?))
       (more-than? 2)))

(defn- safe-hp? [{:keys [hp maxhp] :as player}]
  (>= (/ hp maxhp) 9/10))

(defn- recover
  ([game]
   (recover game false))
  ([{:keys [player] :as game} safe?]
   (if-not (safe-hp? player)
     (or (if-let [[slot _] (and (free-finger? player)
                                (have game "ring of regeneration"
                                      #{:noncursed}))]
           (with-reason "recover - regen"
             (make-use game slot)))
         (if safe?
           (with-reason "recovering - exploring nearby items"
             (:step (navigate game :new-items {:no-fight true :no-autonav true
                                               :no-traps true :explored true
                                               :max-steps 10}))))
         (with-reason "moving to safer position"
           (:step (navigate game
                            (complement (partial exposed? game (curlvl game)))
                            {:max-steps 8 :no-traps true :explored true
                             :no-fight true})))
         (with-reason "recovering" (->Repeated (->Wait) 10))))))

(defn retreat [{:keys [player] :as game}]
  (with-reason "retreating"
    (if (low-hp? player)
      (let [level (curlvl game)
            tile (at level player)
            threats (threat-map game)
            adjacent (->> (neighbors player)
                          (keep (partial monster-at level))
                          (filter hostile?))]
        (or (pray-for-hp game)
            ; TODO escape items - teleportation, digging, perma-e
            (kill-engulfer game)
            (if (and (some (every-pred (complement :fleeing)
                                       (complement passive?)) adjacent)
                     (not-any? ignores-e? adjacent)
                     (can-engrave? game))
              (if (engravable? tile)
                (with-reason "retreat engrave"
                  (engrave-e game (not-any? ignores-e? (vals threats))))
                (if-let [t (find-first #(and (engravable? %)
                                             (not (monster-at level %))
                                             (less-than?
                                               (count adjacent)
                                               (filter threats (neighbors %))))
                                       (neighbors level tile))]
                  (with-reason "moving to neighbor tile to engrave"
                    (->Move (towards player t))))))
            (if (or (empty? threats)
                    (and (perma-e? tile)
                         (not-any? ignores-e? (vals threats))))
              (recover game))
            (if-let [{:keys [step target]} (navigate game stairs-up?
                                                     #{:no-fight :explored
                                                       :no-autonav})]
              (if (stairs-up? (at level player))
                (if (and (seq threats) (not= 1 (dlvl game)))
                  (with-reason "retreating upstairs" ->Ascend)
                  (with-reason "prepared to retreat upstairs" ->Search))
                (if (or (not (:dir step))
                        (stairs-up? (:target step))
                        (not-any? threats (neighbors
                                            (in-direction player (:dir step)))))
                  step)))
            (if-let [nbr (find-first #(and (not (exposed? game level %))
                                           (passable-walking? game level tile %)
                                           (not (monster-at level %))
                                           (not-any? threats (neighbors %)))
                                     (neighbors level tile))]
              (with-reason "running away"
                (->Move (towards tile nbr))))
            (log/debug "retreat failed"))))))

(defn keep-away? [{:keys [player] :as game} m]
  (if (and (pool? (at-curlvl game m))
           (not (have game "oilskin cloak" #{:in-use})))
    true
    (if-let [montype (typename m)]
      (or (some #(.contains montype %)
                ["nymph" "rust monster" "disenchanter" "mind flayer"])
          (and (= "homunculus" montype)
               (not (have-intrinsic? player :sleep)))))))

(defn targettable
  "Returns a list of first hostile monster for each direction (that can be targetted by throw, zap etc.) and there seems to be no risk of hitting non-hostiles or water/lava for non-rays."
  ([game] (targettable game 6 false))
  ([game ray?] (targettable game 5))
  ([{:keys [player] :as game} max-dist ray?]
   (let [level (curlvl game)]
     (for [dir directions
           :let [tiles (->> player
                            (iterate #(in-direction level % dir))
                            (take-while some?)
                            rest
                            (take max-dist))]
           ; TODO bounce rays
           :when (or ray? (not-any? (some-fn pool? lava?) tiles))
           :when (not-any? :room tiles)
           :let [monsters (->> tiles
                               (take-while (some-fn walkable? boulder?))
                               (keep (partial monster-at level)))]
           :when (every? hostile? monsters)
           :let [target (first monsters)]
           :when (and target (not (:remembered target)))]
       target))))

(defn- use-rings [{:keys [player] :as game} threats]
  (or (if-let [[slot _] (and (free-finger? player)
                             (not (:minetown (:tags (curlvl game))))
                             (not-any? :room (neighbors (curlvl game) player))
                             (more-than? 3 threats)
                             (have game "ring of conflict" #{:noncursed}))]
        (with-reason "conflict for combat"
          (make-use game slot)))
      (if-let [[slot _] (and (free-finger? player)
                             (or (and (not-any? sees-invisible? threats)
                                      (more-than? 1 threats))
                                 (some (every-pred (complement sees-invisible?)
                                                   (partial keep-away? game))
                                       threats))
                             (have game "ring of invisibility" #{:noncursed}))]
        (with-reason "invis for combat"
          (make-use game slot)))))

(defn hits-hard? [m]
  (= "winged gargoyle" (typename m)))

(defn fight [{:keys [player] :as game}]
  (let [level (curlvl game)
        nav-opts {:adjacent true
                  :no-traps true
                  :no-autonav true
                  :walking true
                  :max-steps (hostile-dist-thresh game)}]
    (or (kill-engulfer game)
        ; TODO special handling of uniques
        (let [threats (->> (hostile-threats game)
                           (remove (partial can-ignore? game))
                           set)
              adjacent (->> (neighbors player)
                            (keep (partial monster-at level))
                            (filter hostile?)
                            (remove (partial can-ignore? game)))]
          (or (if (and (not (e? (at-player game)))
                       (or (more-than? 1 (remove (some-fn :fleeing
                                                          ignores-e?) adjacent))
                           (some (every-pred (partial keep-away? game)
                                             (complement :fleeing))
                                 adjacent)))
                (with-reason "fight engrave"
                  (engrave-e game)))
              (if (and (exposed? game level player)
                       (more-than? 1 (filter (partial mobile? game) adjacent)))
                (with-reason "moving to non-exposed position"
                  (:step (navigate game #(and (not (exposed? game level %))
                                              (not-any?
                                                (partial monster-at level)
                                                (including-origin neighbors %)))
                                   ; TODO if faster than threats increase max-steps
                                   {:max-steps 2 :no-traps true
                                    :no-fight true :explored true}))))
              (if-let [monster (or (find-first rider? adjacent)
                                   (find-first unique? adjacent)
                                   (find-first priest? adjacent)
                                   (find-first werecreature? adjacent)
                                   (find-first ignores-e? adjacent)
                                   (find-first hits-hard? adjacent)
                                   (find-first nasty? adjacent))]
                (hit game level monster))
              (if-let [m (min-by (partial distance player)
                                 (filter (every-pred (partial keep-away? game)
                                                     (complement :fleeing)
                                                     (complement :remembered)
                                                     (partial mobile? game))
                                         threats))]
                (if (> 3 (distance player m))
                  (with-reason "trying to keep away from" m
                    (engrave-e game))))
              (if-let [m (find-first #(and (keep-away? game %)
                                           (not (adjacent? player %)))
                                     (targettable game))]
                (with-reason "keep-away monster" m
                  (ranged game m)))
              (when-let [{:keys [step target]} (navigate game threats nav-opts)]
                (let [monster (monster-at level target)]
                  (with-reason "targetting enemy" monster
                    (or (use-rings game threats)
                        (hit game level monster)
                        (if (and (more-than? 2 (filter (partial mobile? game)
                                                       threats))
                                 (not (exposed? game level player))
                                 (some->> (:dir step)
                                          (in-direction player)
                                          (exposed? game level)))
                          (with-reason "staying in more favourable position"
                            (if (pos? (rand-int 6))
                              ->Search)))
                        (if-let [m (find-first #(and (= 2 (distance player %))
                                                     (mobile? game %))
                                               threats)]
                          (if (pos? (rand-int (if (slow? m) 50 10)))
                            (with-reason "baiting monsters" ->Search)))
                        step))))))
        (let [leftovers (->> (hostile-threats game)
                             (filter (partial can-ignore? game))
                             (filter (partial can-handle? game))
                             set)]
          (when-let [{:keys [step target]} (navigate game leftovers nav-opts)]
            (let [monster (monster-at level target)]
              (with-reason "targetting leftover enemy" monster
                (or (hit game level monster)
                    step)))))
        (if-let [dir (and (= :sokoban (branch-key game))
                          (= :move (typekw (:last-action game)))
                          (:dir (:last-action game)))]
          (if (and (boulder? (in-direction level player dir))
                   (= (position player) (:last-position game)))
            (if-let [monster (->> (in-direction (in-direction player dir) dir)
                                  (monster-at game))]
              (if (< (+ (:first-known monster) 10) (:turn game))
                (with-reason "ranged attack soko blocker"
                  (ranged game monster)))))))))

(defn- bribe-demon [prompt]
  (->> prompt ; TODO parse amount and pass as arg in the scraper, not in bot logic
       (re-first-group #"demands ([0-9][0-9]*) zorkmids for safe passage")
       parse-int))

(defn- pause-handler [anbf]
  (reify FullFrameHandler
    (full-frame [_ _]
      (when (pause-condition? @(:game anbf))
        (log/debug "pause condition met")
        (pause anbf)))))

(defn- eat-all? [{:keys [player] :as game}]
  (or (hungry? player)
      (> 1000 (nutrition-sum game))
      (and (have-intrinsic? game :fire)
           (have-intrinsic? game :poison))))

(defn- feed [{:keys [player] :as game}]
  (if-not (or (satiated? player) (overloaded? player))
    (let [beneficial? #(every-pred
                         (partial fresh-corpse? game %)
                         (partial want-to-eat? player))
          edible? #(every-pred
                     (partial fresh-corpse? game %)
                     (partial can-eat? player))]
      (or (if-let [p (navigate game #(and (some (beneficial? %) (:items %))))]
            (with-reason "want to eat corpse at" (:target p)
              (or (:step p)
                  (->> (at-player game) :items
                       (find-first (beneficial? player)) :label
                       ->Eat
                       (without-levitation game)))))
          (if (eat-all? game)
            (if-let [p (navigate game #(and (some (edible? %) (:items %))))]
              (with-reason "going to eat corpse at" (:target p)
                (or (:step p)
                    (->> (at-player game) :items
                         (find-first (edible? player)) :label
                         ->Eat
                         (without-levitation game))))))))))

(defn offer-amulet [game]
  (let [tile (and (= :astral (:branch-id game))
                  (at-player game))]
    (if (and (altar? tile) (= (:alignment (:player game)) (:alignment tile)))
      (->Offer (firstv (have game real-amulet?))))))

(defn detect-portal [anbf]
  (reify ActionHandler
    (choose-action [this {:keys [player] :as game}]
      (if-let [[scroll s] (and (= :water (branch-key game))
                               (have game "scroll of gold detection"
                                     #{:safe-buc :bagged}))]
        (with-reason "detecting portal"
          (or (unbag game scroll s)
              (when (confused? player)
                (deregister-handler anbf this)
                (->Read scroll))
              (if-let [[potion p] (and (not-any? #(and (> 4 (distance player %))
                                                       (hostile? %))
                                                 (curlvl-monsters game))
                                       (have game #{"potion of confusion"
                                                    "potion of booze"}
                                             #{:nonblessed :bagged}))]
                (with-reason "confusing self"
                  (or (unbag game potion p)
                      (->Quaff potion))))))))))

(defn- seek-fountain [game]
  (with-reason "seeking a fountain to make Excal"
    (let [oracle (get-level game :main :oracle)]
      (or (if (or (nil? oracle)
                  (not-any? :seen (neighbors oracle oracle-position)))
            (or (seek-level game :main :oracle)
                (seek game oracle-position {:adjacent true})))
          (if (some fountain? (tile-seq oracle))
            (seek-level game :main :oracle))
          (if-let [{:keys [step]} (and (not (:minetown (curlvl-tags game)))
                                       (navigate game fountain?))]
            step
            (or (some->> (:dlvl oracle) (iterate prev-dlvl) rest
                         (take-while (partial not= "Dlvl:0"))
                         (find-first (comp (partial some fountain?) tile-seq
                                           (partial get-level game :main)))
                         (seek-level game :main))
                (seek-feature game :fountain)))))))

(defn make-excal
  "When we have appropriate armor and xp, dip for Excalibur"
  [{:keys [player] :as game}]
  (if-let [[slot _] (and (<= 5 (:xplvl player))
                         (or (<= (:ac player) 3)
                             (get-level game :mines :end))
                         (have game "long sword"))]
    (with-reason "getting Excal"
      (or (seek-fountain game)
          (if (fountain? (at-player game))
            (without-levitation game (->Dip slot \.)))))))

(defn excal-handler [anbf]
  (reify ActionHandler
    (choose-action [this game]
      (if (have game "Excalibur")
        (do (deregister-handler anbf this)
            (log/warn "got excal"))
        (make-excal game)))))

(defn rob? [m]
  (#{"dwarf" "dwarf lord" "dwarf king" "hobbit"} (typename m)))

(defn rob-peacefuls [{:keys [player] :as game}]
  (let [level (curlvl game)]
    (or (if (and (safe-from-guards? level)
                 (not (shop? (at level player))))
          (if-let [{:keys [step target]}
                   (navigate game #(if-let [monster (monster-at level %)]
                                     (and (not (unicorn? monster))
                                          (or (blocked? %) (rob? monster))))
                             #{:adjacent})]
            (with-reason "robbing a poor peaceful dorf"
              (or step (->Attack (towards player target)))))))))

(defn wander [game]
  (with-reason "wandering"
    (or (explore game)
        (search-level game 1)
        (navigate game (->> (curlvl game) tile-seq
                            (filter :walked)
                            (min-by :walked))))))

(defn- hunt-action [{:keys [player] :as game} robbed-of]
  (if-let [[_ dlvl branch _] (first robbed-of)]
    (let [level (curlvl game)
          stealers (filter steals? (vals (:monsters level)))
          recent (max-by :known stealers)]
      (with-reason "seeking monsters that stole my items:" robbed-of
        (or (seek-level game branch dlvl)
            (if-let [[slot _] (and (or (not recent)
                                       (< 11 (- (:turn game) (:known recent))))
                                   (have-intrinsic? player :telepathy)
                                   (have game blind-tool #{:noncursed}))]
              (make-use game slot))
            (if-let [step (:step (navigate game (nav-targets stealers)))]
              step ; expect fight to take over when close enough
              (wander game)))))))

(defn- found-item? [found [_ _ _ item]]
  (some #(and (= (select-keys item [:specific :proof :name :enchantment])
                 (select-keys % [:specific :proof :name :enchantment])))
        found))

(defn hunt [{:keys [game] :as anbf}]
  (let [robbed-of (atom [])] ; [turn dlvl branch item]
    (reify
      ActionHandler
      (choose-action [_ game]
        (hunt-action game @robbed-of))
      AboutToChooseActionHandler
      (about-to-choose [_ {:keys [player] :as game}]
        (if (not= @robbed-of
                  (swap! robbed-of (partial removev #(< 3000 (- (:turn game)
                                                                (first %))))))
          (log/debug "forgetting about stolen items, now missing" @robbed-of))
        (if (and (blind? player)
                 (have-intrinsic? player :telepathy)
                 (not-any? steals? (curlvl-monsters game)))
          (swap! robbed-of (partial removev (fn same-level? [[_ dlvl branch _]]
                                              (and (= dlvl (:dlvl game))
                                                   (= (branch-key game branch)
                                                      (branch-key game))))))))
      FoundItemsHandler
      (found-items [_ items]
        (if (not= @robbed-of
                  (swap! robbed-of (partial removev #(found-item? items %))))
          (log/debug "found stolen items, now missing" @robbed-of)))
      ToplineMessageHandler
      (message [_ msg]
        (when-let [label (re-first-group #" (?:stole|snatches) ([^.!]*)[.!]"
                                         msg)]
          (log/debug "robbed of" label)
          (if-let [[_ item] (inventory-label @game label)]
            (swap! robbed-of conj
                   [(:turn @game) (:dlvl @game) (:branch-id @game) item])
            (log/warn "stolen item" label "not in inventory?")))))))

(defn- have-dsm [game]
  (have game #{"silver dragon scale mail" "gray dragon scale mail"}))

(defn wish [game]
  (cond
    (and (#{:engrave :zapwand} (typekw (:last-action game)))
         (not (have game "scroll of charging" #{:blessed :bagged}))
         (not (have game "scroll of charging" #{:wished :bagged}))
         (not= "recharged"
               (:specific (inventory-slot game (:slot (:last-action game))))))
    "2 blessed scrolls of charging"
    (and (below-medusa? game) (not (have-levi game)))
    "blessed ring of levitation"
    (and (below-medusa? game)
         (not (some (:genocided game) #{";" "electric eel"})))
    "2 blessed scrolls of genocide"
    (and (not (have-dsm game))
         (not (have game "cloak of magic resistance")))
    "blessed greased +3 gray dragon scale mail"
    (not (have-dsm game))
    "blessed greased +3 silver dragon scale mail"
    (and (have-dsm game) (not (have game #{"amulet of reflection"
                                           "shield of reflection"})))
    "blessed greased fixed +3 shield of reflection"
    (not (every? (:genocided game) #{"L" ";"}))
    "2 blessed scrolls of genocide"
    (and (not (have game "helm of telepathy"))
         (not (:see-invis (:intrinsics (:player game)))))
    "blessed fixed +3 helm of telepathy"
    (not (have game "speed boots"))
    "blessed fixed +3 speed boots"
    (not (every? (:genocided game) #{"mind flayer" "master mind flayer"}))
    "2 uncursed scrolls of genocide"
    (not (have game "gauntlets of power"))
    "blessed fixed +3 gauntlets of power"
    :else "2 blessed scrolls of genocide"))

(defn- want-buc? [game item]
  (and (nil? (:buc item))
       ((not-any-fn? food? gem? statue? wand? ammo? dagger?) item)
       (or (know-id? game item)
           (:safe (item-id game item)))))

(defn use-features [{:keys [player] :as game}]
  (or (if-let [[slot ring] (have game #(and (ring? %)
                                            (not (know-id? game %))))]
        (with-reason "drop ring in sink"
          (if-let [{:keys [step]} (navigate game sink?)]
            (or step (->Drop slot)))))
      (if (have game "Excalibur" #{:can-use})
        ; TODO remove items from tile
        (if-let [{:keys [step target]} (navigate game
                                                 #(and (sink? %)
                                                       (not (blocked? %))
                                                       (not (:ring (:tags %)))
                                                       (empty? (:items %)))
                                                 #{:adjacent})]
          (with-reason "kick sink"
            (or step
                (if (monster-at game target)
                  (fidget game (curlvl game) target))
                (kick game target)))))
      (if (altar? (at-player game))
        (if-let [[slot item] (have game {:can-remove true
                                         :bagged true :know-buc false})]
          (with-reason "dropping things on altar"
            (or (unbag game slot item)
                (remove-use game slot)
                (->Drop slot)))))
      ; TODO altars not on current level
      (if (have game (partial want-buc? game) #{:can-remove :bagged})
        (with-reason "going to altar" (:step (navigate game altar?))))
      (if-let [{:keys [step]}
               (and (or (not (:castle (curlvl-tags game)))
                        (< (:ac player) -3))
                    (navigate game (every-pred throne? (comp empty? :items))))]
        ; TODO remove items from tile
        (or (with-reason "going to throne" step)
            ; TODO drop gold
            (with-reason "sitting on throne" ->Sit)))
      (if-let [drawbridge (find-first drawbridge? (tile-seq (curlvl game)))]
        (with-reason "destroy drawbridge"
          (if-let [[slot _] (have game "wand of striking")]
            (if-let [{:keys [step]}
                     (navigate game #(and (= 3 (distance drawbridge %))
                                          (in-line drawbridge %)))]
              (or step (->ZapWandAt slot (towards player drawbridge)))))))))

(defn kill-medusa [anbf]
  (reify ActionHandler
    (choose-action [this {:keys [player] :as game}]
      (with-reason "killing medusa"
        (if-let [medusa (and (not (reflection? game))
                             (get-level game :main :medusa))]
          (if (or (and (:medusa-1 (curlvl-tags game))
                       (:seen (at medusa {:x 38 :y 11})))
                  (and (:medusa-2 (curlvl-tags game))
                       (:seen (at medusa {:x 70 :y 11}))))
            (deregister-handler anbf this)
            (if-let [[slot item] (have game blind-tool #{:noncursed})]
              (or (if (and (stairs-up? (at-player game))
                           (= (prev-dlvl (:dlvl game)) (:dlvl medusa)))
                    (or (make-use game slot) ->Ascend))
                  (if (and (= (:dlvl game) (:dlvl medusa))
                           (> 20 (distance player {:x 38 :y 11})))
                    (go-down game medusa))))))))))

(defn safe-zap? [game dir]
  (let [level (curlvl game)]
    (every? #(and ((some-fn corridor? floor? water? door-open?) %)
                  (not (and (= :sokoban (:branch-id game)) (boulder? %)))
                  (not (monster-at level %)))
            (->> (in-direction level (:player game) dir)
                 (iterate #(in-direction level % dir))
                 (take 7)))))

(defn itemid [{:keys [player] :as game}]
  (or (if (can-engrave? game)
        (if-let [[slot w] (have game #(and (nil? (:engrave (item-id game %)))
                                           (not (tried? game %))
                                           (wand? %)) #{:nonempty})]
          (with-reason "engrave-id wand" w
            (or (:step (navigate game engravable?))
                (if-not (:engraving (at-player game))
                  (engrave-e game))
                (->Engrave slot "Elbereth" true)))))
      (if-let [[slot wand] (have game #(and (nil? (:target (item-id game %)))
                                            (wand? %)) #{:nonempty})]
        (if-let [dir (find-first (partial safe-zap? game) directions)]
          (with-reason "zap-id wand" wand
            (->ZapWandAt slot dir))))
      (if-let [[slot item] (have game (every-pred (partial should-try? game)
                                                  (complement wand?))
                                 #{:safe-buc :bagged})]
        (with-reason "trying out safe item"
          (or (unbag game slot item)
              (make-use game slot))))
      (if-let [[slot item] (and (:room (at-player game))
                                (shop-inside? (curlvl game) (:player game))
                                (have game #(and (price-id? game %)
                                                 (not (:cost %))
                                                 ((shops-taking %)
                                                  (:room (at-player game))))
                                      #{:bagged}))]
        (with-reason "price id (sell)"
          (or (unbag game slot item)
              (->Drop slot))))
      (if-let [shoptype (->> (have-all game #(price-id? game %) #{:bagged})
                             (mapcat (comp (partial shops-taking) secondv))
                             (some (curlvl-tags game)))]
        (with-reason "visit shop" shoptype "to price id items"
          (:step (navigate game #(and (= shoptype (:room %))
                                      (shop-inside? (curlvl game) %))))))))

(defn- want-name? [item]
  (and (ambiguous-appearance? item) (not (gem? item)) (not (candle? item))))

(defn shop [{:keys [player] :as game}]
  (let [want? #(and (:cost %) (or (want-buy? game %)
                                  (want-name? %)))]
    (or (if-let [item (find-first want? (:items (at-player game)))]
          (with-reason "want to call item"
            (->PickUp (:label item))))
        (with-reason "visit shop for items"
          (:step (navigate game #(some want? (:items %))))))))

(defn bag-items [game]
  ; TODO
  )

(defn- id-priority [game item]
  (let [know? (know-id? game item)
        price (:price (item-id game item))]
    (cond-> 0
      (food? item) (- 10)
      (rocks? item) (- 10)
      (not know?) (+ 2)
      (nil? (:buc item)) inc
      (some @desired (possible-ids game item)) (+ 5)
      (and (not know?)
           (not (#{100 200} price))
           (scroll? item)) (+ 10)
      (and (not know?)
           (could-be? game "scroll of remove curse" item)) (+ 10)
      (and (not know?)
           (could-be? game "scroll of genocide" item)) (+ 8)
      (and (not know?)
           (= 200 price)
           (or (could-be? game "ring of levitation" item)
               (could-be? game "ring of regeneration" item))) (+ 8)
      (and (wand? item) (not= 150 price)) (+ 5)
      (and (not know?)
           ((some-fn ring? amulet?) item)) (+ 5))))

(defn- want-id
  ([game] (want-id game false))
  ([game bagged?]
   (->> (inventory game bagged?)
        (remove (every-pred (comp some? :buc) (comp some? :enchantment)))
        (sort-by (comp (partial id-priority game) secondv))
        reverse)))

#_(def game @(:game anbf.main/a))
#_(log/debug "want identified\n"
              (map (comp #(str % \newline)
                         (juxt (partial id-priority game)
                               :label) secondv) (want-id game :bagged)))

(defn- safe-enchant? [item]
  (case (item-type item)
    :weapon (> 6 (enchantment item))
    :armor (> 4 (enchantment item))
    nil))

(defn- recharge [game slot]
  (if-not (charged? (inventory-slot game slot))
    (if-let [[s item] (or (have game "scroll of charging" #{:blessed :bagged})
                          (have game "scroll of charging" #{:wished :bagged}))]
      (with-reason "recharge"
        (or (unbag game s item)
            (with-handler
              (reify ChargeWhatHandler
                (charge-what [_ _] slot))
              (->Read s)))))))

(defn use-items [{:keys [player] :as game}]
  (if-not (shop? (at-player game))
    (or (if-let [[excal i] (have game "Excalibur" #{:can-use})]
          (if-let [[scroll _] (and (< (enchantment i) 7)
                                   (have game "scroll of enchant weapon"
                                         #{:bagged :noncursed}))]
            (or (with-reason "enchant excal"
                  (or (make-use game excal)
                      (->Read scroll))))))
        (if-let [[scroll si] (have game "scroll of enchant armor"
                             #{:bagged :noncursed})]
          (with-reason "enchant armor"
            (if (have game (every-pred safe-enchant? armor? :in-use))
              (if-let [slots (->> (have-all game (every-pred
                                                   (complement safe-enchant?)
                                                   armor? :in-use))
                                  (map firstv) seq)]
                (if (every? #(can-remove? game %) slots)
                  (remove-use game (first slots)))
                (or (unbag game scroll si)
                    (->Read scroll))))))
        (if-let [[slot item] (have game #{"potion of gain level"
                                          "potion of see invisible"}
                                   #{:bagged})]
          (with-reason "helpful potion"
            (or (unbag game slot item)
                (->Quaff slot))))
        (if-let [[slot item] (have game "magic lamp" #{:noncursed :bagged})]
          (with-reason "rubbing lamp"
            (or (unbag game slot item)
                (bless game slot)
                (->Rub slot))))
        (if-let [[slot geno] (have game "scroll of genocide"
                                #{:bagged :noncursed})]
          (with-reason "geno"
            (or (unbag game slot geno)
                (bless game slot)
                (->Read slot))))
        (if-let [[slot wow] (have game "wand of wishing" #{:bagged})]
          (with-reason "wish"
            (or (unbag game slot wow)
                (recharge game slot)
                (if (charged? wow)
                  (->ZapWand slot)))))
        (if-let [[slot item] (have game "scroll of identify"
                                   #{:bagged :noncursed})]
          (when-let [want (seq (want-id game :bagged))]
            (if (< 6 (id-priority game (secondv (first want))))
              (or (keep-first (fn [[slot item]]
                                (unbag game slot item)) want)
                  (with-reason "identify" (first want)
                    (or (unbag game slot item)
                        (->Read slot)))))))
        (if-let [[slot item] (have game #{"potion of extra healing"
                                          "potion of full healing"} #{:bagged})]
          (if (= (:hp player) (:maxhp player))
            (with-reason "improve maxhp"
              (or (unbag game slot item)
                  (->Quaff slot))))))))

(defn choose-identify [game options]
  (let [want (want-id game)]
    (find-first options (map firstv want))))

(defn- respond-geno []
  (let [geno-classes (atom (list ";" "L" "R" "c" "n" "m" "N" "q" "T" "U"))
        geno-types (atom (list "electric eel" "master mind flayer" "mind flayer"
                               "disenchanter" "green slime" "golden naga"
                               "gremlin"))
        next! (fn [g]
                (when-let [res (peek @g)]
                  (swap! g pop)
                  res))]
    (reify GenocideHandler
      (genocide-class [_ _] (next! geno-classes))
      (genocide-monster [_ _] (next! geno-types)))))

(defn random-unihorn [game]
  (if-let [[slot _] (and (zero? (rand-int 200)) (have-unihorn game))]
    (with-reason "randomly use unihorn" (->Apply slot))))

(defn get-protection [{:keys [player] :as game}]
  (if (and (want-protection? game) (> (gold game) (* 400 (:xplvl player))))
    (with-reason "get protection"
      (or (if-let [priest (find-first (every-pred priest? :peaceful
                                                  (partial adjacent? player))
                                      (curlvl-monsters game))]
            (->Contribute (towards player priest) (* 400 (:xplvl player))))
          (if (:temple (:tags (curlvl game)))
            (or (some->> (curlvl-monsters game)
                         (find-first (every-pred priest? :peaceful))
                         (navigate game) :step)
                (:step (navigate game (every-pred altar? temple?)))))
          (if-let [target (find-first (comp :temple :tags) (level-seq game))]
            (seek-level game (:branch-id target) (:dlvl target)))))))

#_(defn rub-id [{:keys [game] :as anbf}]
  (let [torub (atom 8)]
    (reify ActionHandler
      (choose-action [this game]
        (if (know-appearance? game "magic lamp")
          (deregister-handler anbf this))
        (when-let [[slot item] (have game (partial could-be? game "magic lamp")
                                     #{:noncursed})]
          (if (zero? (swap! torub dec))
            (swap! game identify-slot slot "oil lamp"))
          (with-reason "rub-id" (->Rub slot)))))))

(defn handle-drowning [{:keys [player] :as game}]
  (if (and (:grabbed player) (some pool? (neighbors game player)))
    (let [level (curlvl game)
          [drowner & _ :as drowners] (filter #(and (pool? %)
                                                   (monster-at level %))
                                             (neighbors level player))]
      (with-reason "grabbed - avoid drowning"
        (if drowner
          (or (pray game)
              (if-let [[slot ring] (have-levi-on game)]
                (if (and (ring? ring) (walkable? (at-player game)))
                  (remove-use game slot)))
              (engrave-e game :perma)
              (if-let [[slot wand] (and (less-than? 2 drowners)
                                        (or (have game "wand of teleportation")
                                            (have game "wand of cold")))]
                (->ZapWandAt slot (towards player drowner)))
              (engrave-e game)))))))

(defn init [{:keys [game] :as anbf}]
  (-> anbf
      (register-handler priority-bottom (pause-handler anbf))
      (register-handler (reify ChooseCharacterHandler
                          (choose-character [this]
                            (deregister-handler anbf this)
                            "nvd"))) ; choose a dwarven valk
      (register-handler (reify
                          IdentifyWhatHandler
                          (identify-what [_ options]
                            (choose-identify @game options))
                          OfferHandler
                          (offer-how-much [_ _]
                            (bribe-demon (:last-topline @(:game anbf))))
                          ReallyAttackHandler
                          (really-attack [_ _] false)))
      (register-handler (reify VaultGuardHandler
                          (who-are-you [_ _] "Croesus")))
      (register-handler (respond-geno))
      (register-handler (reify MakeWishHandler
                          (make-wish [_ _]
                            (wish @game))))
      (register-handler (reify AboutToChooseActionHandler
                          (about-to-choose [_ game]
                            (if (or (= :inventory (typekw (:last-action* game)))
                                    (empty? @desired)
                                    (some-> game :last-state at-player shop?)
                                    (shop? (at-player game)))
                              ; expensive (~3 ms)
                              (reset! desired (currently-desired game))))))
      ; expensive action-decision handlers could easily be aggregated and made to run in parallel as thread-pooled futures, dereferenced in order of their priority and cancelled when a decision is made
      (register-handler -99 (reify ActionHandler
                              (choose-action [_ game]
                                (offer-amulet game))))
      (register-handler -15 (reify ActionHandler
                              (choose-action [_ game]
                                (enhance game))))
      (register-handler -14 (name-first-amulet anbf))
      (register-handler -12 (reify ActionHandler
                              (choose-action [_ game]
                                (handle-drowning game))))
      (register-handler -10 (reify ActionHandler
                              (choose-action [_ game]
                                (handle-starvation game))))
      (register-handler -8 (detect-portal anbf))
      (register-handler -7 (reify ActionHandler
                             (choose-action [_ game]
                               (handle-illness game))))
      (register-handler -6 (reify ActionHandler
                             (choose-action [_ game]
                               (retreat game))))
      (register-handler -4 (reify ActionHandler
                             (choose-action [_ game]
                               (fight game))))
      (register-handler -3 (kill-medusa anbf))
      (register-handler -2 (reify ActionHandler
                             (choose-action [_ game]
                               (handle-impairment game))))
      (register-handler 0 (reify ActionHandler
                            (choose-action [_ game]
                              (reequip game))))
      (register-handler 1 (reify ActionHandler
                            (choose-action [_ game]
                              (feed game))))
      (register-handler 2 (reify ActionHandler
                            (choose-action [_ game]
                              (consider-items-here game))))
      (register-handler 3 (reify ActionHandler
                             (choose-action [_ game]
                               (recover game :safe))))
      (register-handler 4 (reify ActionHandler
                            (choose-action [_ game]
                              (examine-containers game))))
      (register-handler 5 (reify ActionHandler
                            (choose-action [_ game]
                              (examine-containers-here game))))
      (register-handler 6 (reify ActionHandler
                            (choose-action [_ game]
                              (consider-items game))))
      (register-handler 7 (reify ActionHandler
                            (choose-action [_ game]
                              (use-items game))))
      (register-handler 8 (hunt anbf))
      (register-handler 9 (reify ActionHandler
                            (choose-action [_ game]
                              (use-features game))))
      (register-handler 9 (reify ActionHandler
                            (choose-action [_ game]
                              (random-unihorn game))))
      (register-handler 10 (reify ActionHandler
                            (choose-action [_ game]
                              (itemid game))))
      (register-handler 11 (reify ActionHandler
                            (choose-action [_ game]
                              (shop game))))
      (register-handler 12 (reify ActionHandler
                             (choose-action [_ game]
                               (bag-items game))))
      (register-handler 14 (reify ActionHandler
                            (choose-action [_ game]
                              (get-protection game))))
      (register-handler 15 (excal-handler anbf))
      (register-handler 16 (reify ActionHandler
                            (choose-action [this game]
                              (rob-peacefuls game))))
      (register-handler 18 (reify ActionHandler
                             (choose-action [_ game]
                               (progress game))))))
