"""Parser del export "Sentence pairs" jpn→eng de Tatoeba."""
from dataclasses import dataclass


@dataclass
class Oracion:
    id: int
    japones: str
    ingles: str


def parsear_pares(ruta: str) -> list:
    oraciones = []
    vistos = set()
    with open(ruta, encoding='utf-8') as f:
        for linea in f:
            campos = linea.rstrip('\n').split('\t')
            if len(campos) < 4:
                continue  # línea malformada: ignorar, no frenar el build
            id_jpn, japones, _, ingles = campos[0], campos[1], campos[2], campos[3]
            if id_jpn in vistos:
                continue  # ya tenemos una traducción para esta oración
            vistos.add(id_jpn)
            oraciones.append(Oracion(int(id_jpn), japones, ingles))
    return oraciones
