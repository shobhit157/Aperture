# Kademlia — Triangle Property and Euclidean Distance

*DHT peer-routing algorithm — organizes peers by ID distance (XOR) using the triangle inequality for provably correct lookups.*

---

## 1. What Kademlia is

Kademlia is an algorithm for organizing a large group of peers so that
any one of them can find any other peer — or find who's responsible for
a piece of data — in just a few steps, with no central directory.

Every peer has a random ID. Kademlia defines a "distance" between any two
IDs, and the entire lookup process is just one simple rule repeated:
*ask whoever you know that's closest to the target; they'll point you to
someone even closer.* For that rule to actually work — to guarantee you
really do get closer each time, rather than wandering randomly — the
distance being used has to behave in a specific, provable way. That
requirement is what connects Kademlia to ordinary geometry.

---

## 2. Euclidean distance — the familiar starting point

Euclidean distance is the distance formula from geometry class — the
straight-line distance between two points:

```text
distance(P1, P2) = sqrt( (x2 - x1)² + (y2 - y1)² )
```

It's the distance a plane would fly, ignoring roads or terrain entirely —
point to point, as the crow flies.

Euclidean distance has a property that feels obvious once stated:

```text
Flying directly from A to C is never longer than
flying from A to B, then B to C.
```

This is the **triangle inequality**, and it is a direct, provable
consequence of how Euclidean distance is defined geometrically — three
points always form a triangle (possibly a flat, degenerate one), and no
side of a triangle can be longer than the sum of the other two.

---

## 3. Where a "distance" can break this — roads

Not every real-world notion of distance keeps this property. Road-network
distance does not:

```text
A "direct" road from A to C can genuinely be longer than
going A → B → C, if the direct road winds through difficult
terrain and the route through B uses better roads.
```

This matters because it shows the triangle inequality is **not** an
automatic law that every kind of "distance" must obey — it's a specific
property that only some distance definitions actually have.

---

## 4. Kademlia's distance: XOR, not Euclidean

Kademlia peers don't have coordinates on a map — they only have IDs
(random numbers). So there's no `(x, y)` to plug into the Euclidean
formula at all. Kademlia instead defines distance as the **bitwise XOR**
of two IDs, read as a number:

```text
  0101   (peer A)
⊕ 0111   (peer B)
--------
  0010   → distance = 2
```

> [!IMPORTANT]
> This has no relationship to physical location. Two peers next door to
> each other can XOR to a large distance; two peers on opposite sides of
> the planet can XOR to a small one. It is a similarity score on bit
> patterns, not a geometric measurement.

---

## 5. Connecting the two — why XOR is allowed to be called a "distance" at all

Euclidean distance earns the triangle inequality from actual geometry —
three real points in space, forming a real triangle. XOR distance has no
geometry to draw on at all. And yet it satisfies the exact same property:

```text
d(A, C) ≤ d(A, B) + d(B, C)
```

This is provable directly from the algebra of XOR:

```text
A XOR C = A XOR B XOR B XOR C        (insert B XOR B, which is 0)
        = (A XOR B) XOR (B XOR C)

For any two non-negative numbers X, Y:
        X XOR Y ≤ X + Y
(XOR is "addition without carrying" — ordinary addition can only
 equal or exceed XOR, never fall below it)

Therefore:
        d(A, C) = (A XOR B) XOR (B XOR C)
                ≤ (A XOR B) + (B XOR C)
                = d(A, B) + d(B, C)
```

> [!IMPORTANT]
> Euclidean distance and XOR distance arrive at the identical
> triangle-inequality guarantee through two completely different routes —
> one from geometry, one from bit arithmetic. XOR was chosen as
> Kademlia's distance specifically *because* it reaches this same
> guarantee, not because it resembles Euclidean distance in any other way.

---

## 6. Why this connection actually matters

The triangle inequality is what makes Kademlia's core routing rule
provably correct: *"ask whoever is closer, they'll point you closer
still."* This only works as a reliable strategy if "closer" genuinely
means real progress every time — exactly what road-network distance
cannot guarantee, and exactly what Euclidean distance can. By choosing a
metric (XOR) that satisfies the same inequality Euclidean distance does,
Kademlia borrows geometry's most important routing guarantee without
needing any actual coordinates, map, or physical space at all.
