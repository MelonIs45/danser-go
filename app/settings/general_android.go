//go:build android

package settings

// On Android, Songs/Skins/Replays live inside the app's own storage. Returning an empty
// base keeps the paths relative, so they resolve against env.DataDir() once it's set
// (this runs during package init, before the environment is initialized).
func getOsuInstallation() string {
	return ""
}
