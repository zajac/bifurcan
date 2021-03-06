(ns bifurcan.durable-test
  (:require
   [primitive-math :as p]
   [byte-streams :as bs]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer :all]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]
   [clojure.test.check.clojure-test :as ct :refer [defspec]]
   [bifurcan.collection-test :as coll]
   [bifurcan.test-utils :as u :refer [iterations]])
  (:import
   [java.nio
    ByteBuffer]
   [io.lacuna.bifurcan
    IMap
    Map
    List
    Maps
    IEntry
    DurableInput
    DurableOutput
    DurableMap
    DurableList
    DurableEncodings
    DurableEncodings$Codec]
   [io.lacuna.bifurcan.utils
    Bits]
   [io.lacuna.bifurcan.durable.allocator
    GenerationalAllocator]
   [io.lacuna.bifurcan.hash
    PerlHash]
   [io.lacuna.bifurcan.durable.blocks
    HashMap
    SkipTable
    SkipTable$Writer
    SkipTable$Entry]
   [io.lacuna.bifurcan.durable.io
    DurableBuffer
    BufferInput]
   [io.lacuna.bifurcan.durable
    Encodings
    BlockPrefix
    BlockPrefix$BlockType
    ChunkSort
    Bytes]))

(set! *warn-on-reflection* true)

(def gen-pos-int
  (gen/such-that
    #(not= 0 %)
    (gen/fmap
      #(Math/abs (p/int %))
      gen/large-integer)))

(def gen-small-pos-int
  (gen/such-that
    #(not= 0 %)
    (gen/fmap
      #(Math/abs (p/int %))
      gen/int)))

(def edn-encoding
  (DurableEncodings/unityped
    (DurableEncodings/primitive
      "edn"
      4
      (DurableEncodings$Codec/undelimited
       (u/->bi-consumer
         (fn [o ^DurableOutput out]
           (.write out (.getBytes (pr-str o) "utf-8"))))
       (u/->bi-fn
         (fn [^DurableInput in root]
           (let [ary (byte-array (.remaining in))]
             (.readFully in ary)
             (edn/read-string (String. ary "utf-8")))))))))

(defn no-leaks? []
  (and
    (zero? (GenerationalAllocator/diskAllocations))
    (zero? (GenerationalAllocator/memoryAllocations))))

(defn free! [^DurableInput in]
  (.close in)
  (doto (no-leaks?)
    (assert
      (str "we have leaks! disk: " (GenerationalAllocator/diskAllocations)
        " mem: " (GenerationalAllocator/memoryAllocations)))))

;;; Util

(defspec test-uvlq-roundtrip iterations
  (prop/for-all [n gen/large-integer]
    (let [out (doto (DurableBuffer.)
                (.writeUVLQ n))
          in  (.toInput out)]
      (try
        (= n (.readUVLQ in))
        (finally
          (free! in))))))

(defspec test-vlq-roundtrip iterations
  (prop/for-all [n gen/large-integer]
    (let [out (doto (DurableBuffer.)
                (.writeVLQ n))
          in  (.toInput out)]
      (try
        (= n (.readVLQ in))
        (finally
          (free! in))))))

(defspec test-prefixed-vlq-roundtrip iterations
  (prop/for-all [n gen-pos-int
                 bits (gen/choose 0 6)]
    (let [out (DurableBuffer.)
          _   (Encodings/writePrefixedUVLQ 0 bits n out)
          in  (.toInput out)]
      (try
        (= n (Encodings/readPrefixedUVLQ (.readByte in) bits in))
        (finally
          (free! in))))))

;;; Prefix

(defspec test-prefix-roundtrip iterations
  (prop/for-all [n gen-pos-int
                 type (->> (BlockPrefix$BlockType/values)
                        (remove
                          #{BlockPrefix$BlockType/DIFF
                            BlockPrefix$BlockType/COLLECTION
                            BlockPrefix$BlockType/EXTENDED})
                        (map gen/return)
                        gen/one-of)]
    (let [out (DurableBuffer.)
          p   (BlockPrefix. n type)
          _   (.encode p out)
          in  (.toInput out)]
      (try
        (= p (BlockPrefix/decode in))
        (finally
          (free! in))))))

;; SkipTable

(defn create-skip-table [entries]
  (let [writer   (SkipTable$Writer.)
        _        (doseq [[index offset] entries]
                   (.append writer index offset))
        out      (DurableBuffer.)
        _        (.flushTo writer out)
        in       (.toInput out)
        contents (.sliceBlock in BlockPrefix$BlockType/TABLE)
        buf      (Bytes/allocate (.size contents))
        _        (.read contents buf)]
    [in (SkipTable. (.pool (BufferInput. (.flip buf))) (.tiers writer))]))

(defn print-skip-table [^DurableInput in]
  (->> (repeatedly #(when (pos? (.remaining in)) (.readVLQ in)))
    (take-while identity)))

(defspec test-durable-skip-table iterations
  (prop/for-all [init-entry (gen/tuple gen/int gen/int)
                 entry-offsets (gen/such-that
                                 (complement empty?)
                                 (gen/list (gen/tuple gen-small-pos-int gen/int)))]
    (let [entries (reductions #(map + %1 %2) init-entry entry-offsets)
          [in t]  (create-skip-table entries)]
      (try
        (every?
          (fn [[index offset]]
            (let [e (.floor ^SkipTable t index)]
              (and
                (= index (.index e))
                (= offset (.offset e)))))
          entries)
        (finally
          (free! in))))))

;;; SortedChunk

(def hash-fn (u/->to-long-fn hash))

(defspec test-sort-map-entries iterations
  (prop/for-all [entries (gen/list (gen/tuple gen-pos-int gen-pos-int))]
    (let [m (into {} entries)
          m' (->> (HashMap/sortIndexedEntries
                    (Map/from ^java.util.Map m)
                    hash-fn)
               iterator-seq
               (map
                 (fn [^IEntry e]
                   [(.key e) (.value e)])))]
      (and
        (= (sort-by #(hash (key %)) m) m')
        (no-leaks?)))))

;;; DurableMap

(defspec test-durable-map iterations
  (prop/for-all [m (coll/map-gen #(Map.))]
    (let [out (DurableBuffer.)
          _   (DurableMap/encode (-> ^IMap m .entries .iterator) edn-encoding 10 out)
          in  (.toInput out)
          m'  (DurableMap/decode edn-encoding nil (.pool in))]
      (try
        (and
          (= m m')
          (coll/valid-map-indices? m')
          (free! in))))))

;;; DurableList

 (defspec test-durable-list iterations
  (prop/for-all [l (coll/list-gen #(List.))]
    (let [out (DurableBuffer.)
          _   (DurableList/encode (.iterator ^Iterable l) edn-encoding out)
          in  (.toInput out)
          l'  (DurableList/decode edn-encoding nil (.pool in))]
      (and
        (= l l')
        (free! in)))))
