# SmbSynchDroid

Appli Android qui envoie automatiquement chaque nouvelle photo de la tablette vers un partage réseau SMB (NAS), sans rien installer sur le NAS.

## Installation

Télécharger le dernier `smbsynchdroid-N.apk` dans les [Releases](../../releases/latest) et l'installer (autoriser les sources inconnues). Chaque nouvelle version s'installe par-dessus la précédente.

## Réglages

- **Adresse du NAS** : IP ou nom (on peut aussi coller `\\nas\partage\dossier`, l'appli découpe toute seule).
- **Partage / dossier** : où ranger les photos.
- **Utilisateur / mot de passe** : compte SMB ; vide = invité. Le mot de passe est chiffré avec le Keystore Android.
- **Dossier photo de la tablette** : `DCIM/Camera` par défaut (seules les photos, pas les vidéos ; captures d'écran exclues).
- **Rangement** : un seul dossier, ou par année, année/mois, année/mois/jour (date de prise de vue).
- **Photo retouchée** : si une photo déjà envoyée est modifiée sur la tablette (retouche Galerie), soit la retouche est envoyée à côté (`nom_1.jpg`, par défaut), soit elle remplace la photo du NAS.
- **Uniquement en Wi-Fi**.

Au premier enregistrement, seules les photos prises **à partir de ce moment** sont suivies. Le bouton « Envoyer aussi les photos déjà présentes » envoie tout l'historique (les photos déjà sur le NAS sont ignorées).

Penser à appuyer sur **Autoriser en arrière-plan (batterie)**, sinon One UI peut retarder la synchro.

## Fonctionnement

- Android réveille l'appli dès qu'une image est ajoutée à la galerie (WorkManager, déclencheur sur MediaStore), plus une vérification toutes les heures.
- Envoi en SMB2/3 avec [smbj](https://github.com/hierynomus/smbj) : écriture dans `nom.part` puis renommage, donc jamais de fichier à moitié copié.
- Synchro à sens unique : supprimer une photo sur la tablette ne la supprime pas du NAS.
- Si le NAS est injoignable, nouvel essai automatique plus tard ; notification après plusieurs échecs.
- Les retouches sont détectées via la date de modification ; un fichier du même nom mais de taille différente est traité selon le réglage « Photo retouchée ».

## Compilation

GitHub Actions compile l'APK à chaque push sur `main` et le publie en release `smbsynchdroid-<numéro>.apk`.
La clé de signature (`app/smbsynchdroid.keystore`) est volontairement dans le dépôt pour garder la même signature d'une version à l'autre.
